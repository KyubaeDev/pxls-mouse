package space.pxls.server;

import kong.unirest.*;
import com.typesafe.config.Config;
import io.undertow.websockets.core.WebSocketChannel;

import kong.unirest.json.JSONArray;
import org.apache.commons.text.StringEscapeUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import kong.unirest.json.JSONObject;

import space.pxls.App;
import space.pxls.data.DBPixelPlacementFull;
import space.pxls.server.packets.socket.*;
import space.pxls.user.Faction;
import space.pxls.user.User;
import space.pxls.util.TextFilter;
import space.pxls.util.RateLimitFactory;

import java.io.*;
import java.net.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

public class PacketHandler {
    private UndertowServer server;
    private int numAllCons = 0;
    private int previousUserCount = 0;

    private static final Set<User> userBonuses = new HashSet<>();
    private static Timer bonusTimer;

    /**
     * Starts the Twitch subscriber bonus timer.
     * <p>
     * When a user places the last pixel available, they will be added to userBonuses, which is a set of people needing
     * their bonus pixels applied. If their undo window has passed (preventing getting +5 per undo), apply the bonus
     * and remove them from the set in bulk per tick.
     */
    public static void startBonusTimer() {
        bonusTimer = new Timer();
        bonusTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    Iterator<User> iterator = userBonuses.iterator();
                    while (iterator.hasNext()) {
                        User user = iterator.next();

                        if (!user.undoWindowPassed()) continue;
                        int stack = Math.min(user.getStacked() + (App.getStackTwitchBonus() - 1), App.getStackMaxStacked());
                        user.setStacked(stack);

                        iterator.remove();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 500L, 500L);
    }

    public int getCooldown() {
        Config config = App.getConfig();

        String cooldownType = config.getString("cooldownType").toLowerCase();
        if (cooldownType.equalsIgnoreCase("activity")) {
            double x = server.getNonIdledUsersCount();
            double s = config.getDouble("activityCooldown.steepness");
            double u = config.getDouble("activityCooldown.userOffset");
            double t = config.getDouble("activityCooldown.globalOffset");

            // Formula by Atomic10 and c4rt
            // https://www.desmos.com/calculator/sgphb1abzi
            double cooldown = s * Math.sqrt(x + u) + t;

            double multiplier = config.getDouble("activityCooldown.multiplier");
            cooldown *= multiplier;

            return (int) Math.abs(cooldown);
        } else {
            return (int) config.getDuration("staticCooldown.time", TimeUnit.SECONDS);
        }
    }

    public PacketHandler(UndertowServer server) {
        this.server = server;
    }

    public void userdata(WebSocketChannel channel, User user) {
        if (user != null) {
            server.send(channel, new ServerUserInfo(
                    user.getName(),
                    user.getAllRoles(),
                    user.getPixelCount(),
                    user.getAllTimePixelCount(),
                    user.isBanned(),
                    user.getBanExpiryTime(),
                    user.getBanReason(),
                    user.loginsWithIP() ? "ip" : "service",
                    user.getPlaceOverrides(),
                    user.isRenameRequested(true),
                    user.getDiscordName(),
                    user.isTwitchSubbed()
            ));
            sendAvailablePixels(channel, user, "auth");
        }
    }

    public void connect(WebSocketChannel channel, User user) {
        if (user != null) {
            userdata(channel, user);
            sendCooldownData(channel, user);
            user.flagForCaptcha();
            server.addAuthedUser(user);

            user.setInitialAuthTime(System.currentTimeMillis());
            user.tickStack(false); // pop the whole pixel stack
            sendAvailablePixels(channel, user, "connect");
        }
        numAllCons++;
    }

    public void disconnect(WebSocketChannel channel, User user) {
        if (user != null && user.getConnections().size() == 0) {
            server.removeAuthedUser(user);
        }
        numAllCons--;
    }

    public void accept(WebSocketChannel channel, User user, Object obj, String ip) {
        if (user == null) return;
        if (obj instanceof ClientPlace && user.hasPermission("board.place")) handlePlace(channel, user, ((ClientPlace) obj), ip);
        if (obj instanceof ClientUndo && user.hasPermission("board.undo")) handleUndo(channel, user, ((ClientUndo) obj), ip);
        if (obj instanceof ClientCaptcha) handleCaptcha(channel, user, ((ClientCaptcha) obj));
        if (obj instanceof ClientShadowBanMe) handleShadowBanMe(channel, user, ((ClientShadowBanMe) obj));
        if (obj instanceof ClientBanMe) handleBanMe(channel, user, ((ClientBanMe) obj));
        if (obj instanceof ClientAdminPlacementOverrides && user.hasPermission("user.admin")) handlePlacementOverrides(channel, user, ((ClientAdminPlacementOverrides) obj));
        if (obj instanceof ClientAdminMessage && user.hasPermission("user.alert")) handleAdminMessage(channel, user, ((ClientAdminMessage) obj));
    }

    private void handleAdminMessage(WebSocketChannel channel, User user, ClientAdminMessage obj) {
        User u = App.getUserManager().getByName(obj.getUsername());
        if (u != null) {
            ServerAlert msg = new ServerAlert(user.getName(), escapeHtml4(obj.getMessage()));
            App.getDatabase().insertAdminLog(user.getId(), String.format("Sent an alert to %s (UID: %d) with the content: %s", u.getName(), u.getId(), escapeHtml4(obj.getMessage())));
            for (WebSocketChannel ch : u.getConnections()) {
                server.send(ch, msg);
            }
        }
    }

    private void handleShadowBanMe(WebSocketChannel channel, User user, ClientShadowBanMe obj) {
        if (!user.isBanned() && !user.isShadowBanned()) {
            App.getDatabase().insertAdminLog(user.getId(), String.format("shadowban %s with reason: self-shadowban via script; %s", user.getName(), obj.getReason()));
            user.shadowBan(String.format("auto-ban via script; %s", obj.getReason()), 999*24*3600, user);
        }
    }

    private void handleBanMe(WebSocketChannel channel, User user, ClientBanMe obj) {
        if (!user.isBanned() && !user.isShadowBanned()) {
            String app = obj.getReason();
            App.getDatabase().insertAdminLog(user.getId(), String.format("permaban %s with reason: auto-ban via script; %s", user.getName(), app));
            user.ban(0, String.format("auto-ban via script; %s", app), 0, user);
        }
    }

    private void handlePlacementOverrides(WebSocketChannel channel, User user, ClientAdminPlacementOverrides obj) {
        if (obj.hasIgnoreCooldown() != null) {
            user.maybeSetIgnoreCooldown(obj.hasIgnoreCooldown());
        }
        if (obj.getCanPlaceAnyColor() != null) {
            user.maybeSetCanPlaceAnyColor(obj.getCanPlaceAnyColor());
        }
        if (obj.hasIgnorePlacemap() != null) {
            user.maybeSetIgnorePlacemap(obj.hasIgnorePlacemap());
        }
        if (obj.hasIgnoreEndOfCanvas() != null) {
            user.maybeSetIgnoreEndOfCanvas(obj.hasIgnoreEndOfCanvas());
        }

        for (WebSocketChannel ch : user.getConnections()) {
            sendPlacementOverrides(ch, user);
            sendCooldownData(ch, user);
        }
    }

    private void handleUndo(WebSocketChannel channel, User user, ClientUndo cu, String ip){
        if (App.getConfig().getBoolean("endOfCanvas") && !user.hasIgnoreEndOfCanvas()) {
            return;
        }
        if (App.getConfig().getBoolean("oauth.twitch.subOnlyPlacement") && !user.isTwitchSubbed()) {
            return;
        }
        boolean _canUndo = user.canUndo(true);
        if (!_canUndo || user.undoWindowPassed()) {
            return;
        }
        if (user.isShadowBanned()) {
            user.setCooldown(0);
            sendCooldownData(user);
            return;
        }
        boolean gotLock = user.tryGetUndoLock();
        if (gotLock) {
            try {
                DBPixelPlacementFull thisPixel = App.getDatabase().getUserUndoPixel(user);
                Optional<DBPixelPlacementFull> recentPixel = App.getDatabase().getFullPixelAt(thisPixel.x, thisPixel.y);
                if (!recentPixel.isPresent()) return;
                if (thisPixel.id != recentPixel.get().id) return;

                if (user.lastPlaceWasStack()) {
                    // user.setStacked(Math.min(user.getStacked() + 1, App.getConfig().getInt("stacking.maxStacked")));
                    user.setStacked(user.getStacked() + 1);
                    sendAvailablePixels(user, "undo");
                }
                PacketHandler.userBonuses.remove(user);
                user.setCooldown(0);
                DBPixelPlacementFull lastPixel = App.getDatabase().getPixelByID(null, thisPixel.secondaryId);
                if (lastPixel != null) {
                    App.getDatabase().putUserUndoPixel(lastPixel, user, thisPixel.id);
                    App.putPixel(lastPixel.x, lastPixel.y, lastPixel.color, user, false, ip, false, "user undo");
                    user.decreasePixelCounts();
                    broadcastPixelUpdate(lastPixel.x, lastPixel.y, lastPixel.color);
                    ackUndo(user, lastPixel.x, lastPixel.y);
                } else {
                    byte defaultColor = App.getDefaultPixel(thisPixel.x, thisPixel.y);
                    App.getDatabase().putUserUndoPixel(thisPixel.x, thisPixel.y, defaultColor, user, thisPixel.id);
                    user.decreasePixelCounts();
                    App.putPixel(thisPixel.x, thisPixel.y, defaultColor, user, false, ip, false, "user undo");
                    broadcastPixelUpdate(thisPixel.x, thisPixel.y, defaultColor);
                    ackUndo(user, thisPixel.x, thisPixel.y);
                }
                sendAvailablePixels(user, "undo");
                sendCooldownData(user);
                sendPixelCountUpdate(user);
            } finally {
                user.releaseUndoLock();
            }
        }
    }

    private void handlePlace(WebSocketChannel channel, User user, ClientPlace cp, String ip) {
        if (App.getConfig().getBoolean("endOfCanvas") && !user.hasIgnoreEndOfCanvas()) {
            return;
        }
        if (App.getConfig().getBoolean("oauth.twitch.subOnlyPlacement") && !user.isTwitchSubbed()) {
            return;
        }
        if (!cp.getType().equals("pixel")) {
            handlePlaceMaybe(channel, user, cp, ip);
        }
        if (cp.getX() < 0 || cp.getX() >= App.getWidth() || cp.getY() < 0 || cp.getY() >= App.getHeight()) return;
        if (user.isBanned()) return;
        if (!user.canPlaceColor(cp.getColor())) return;

        if (user.canPlace()) {
            boolean gotLock = user.tryGetPlacingLock();
            if (gotLock) {
                try {
                    boolean doCaptcha = (user.isOverridingCaptcha() || App.isCaptchaEnabled()) && App.isCaptchaConfigured();
                    if (doCaptcha) {
                        int pixels = App.getConfig().getInt("captcha.maxPixels");
                        if (!user.isOverridingCaptcha() && pixels != 0) {
                            boolean allTime = App.getConfig().getBoolean("captcha.allTime");
                            doCaptcha = (allTime ? user.getAllTimePixelCount() : user.getPixelCount()) < pixels;
                        }
                    }
                    if (user.updateCaptchaFlagPrePlace() && doCaptcha) {
                        server.send(channel, new ServerCaptchaRequired());
                    } else {
                        int c = App.getPixel(cp.getX(), cp.getY());
                        boolean isInsidePlacemap = App.getCanPlace(cp.getX(), cp.getY());
                        boolean isColorDifferent = c != cp.getColor();
                        
                        int c_old = c;
                        if (user.hasIgnorePlacemap() || (isInsidePlacemap && isColorDifferent)) {
                            int seconds = getCooldown();
                            if (c_old != 0xFF && c_old != -1 && App.getDatabase().shouldPixelTimeIncrease(user.getId(), cp.getX(), cp.getY()) && App.getConfig().getBoolean("backgroundPixel.enabled")) {
                                seconds = (int)Math.round(seconds * App.getConfig().getDouble("backgroundPixel.multiplier"));
                            }
                            if (user.isShadowBanned()) {
                                // ok let's just pretend to set a pixel...
                                App.logShadowbannedPixel(cp.getX(), cp.getY(), cp.getColor(), user.getName(), ip);
                                ServerPlace msg = new ServerPlace(Collections.singleton(new ServerPlace.Pixel(cp.getX(), cp.getY(), cp.getColor())));
                                for (WebSocketChannel ch : user.getConnections()) {
                                    server.send(ch, msg);
                                }
                                ackPlace(user, cp.getX(), cp.getY());
                                if (user.canUndo(false)) {
                                    server.send(channel, new ServerCanUndo(App.getConfig().getDuration("undo.window", TimeUnit.SECONDS)));
                                }
                            } else {
                                boolean modAction = cp.getColor() == 0xFF || user.hasIgnoreCooldown() || (user.hasIgnorePlacemap() && !isInsidePlacemap);
                                App.putPixel(cp.getX(), cp.getY(), cp.getColor(), user, modAction, ip, true, "");
                                broadcastPixelUpdate(cp.getX(), cp.getY(), cp.getColor());
                                ackPlace(user, cp.getX(), cp.getY());
                                sendPixelCountUpdate(user);
                            }
                            if (!user.hasIgnoreCooldown()) {
                                if (user.isIdled()) {
                                    user.setIdled(false);
                                }
                                user.setLastPixelTime();
                                if (user.getStacked() > 0) {
                                    user.setLastPlaceWasStack(true);
                                    user.setStacked(user.getStacked()-1);
                                    sendAvailablePixels(user, "consume");
                                } else {
                                    user.setLastPlaceWasStack(false);
                                    user.setCooldown(seconds);
                                    if (user.isTwitchSubbed()) {
                                        // worse idea?
                                        PacketHandler.userBonuses.add(user);
                                    }
                                    App.getDatabase().updateUserTime(user.getId(), seconds);
                                    sendAvailablePixels(user, "consume");
                                }

                                if (user.canUndo(false)) {
                                    server.send(channel, new ServerCanUndo(App.getConfig().getDuration("undo.window", TimeUnit.SECONDS)));
                                }
                            }

                            sendCooldownData(user);
                        }
                    }
                } finally {
                    user.releasePlacingLock();
                }
            }
        }
    }

    private void handlePlaceMaybe(WebSocketChannel channel, User user, ClientPlace cp, String ip) {
    }

    private void handleCaptcha(WebSocketChannel channel, User user, ClientCaptcha cc) {
        if (!user.isFlaggedForCaptcha()) return;
        if (user.isBanned()) return;

        Unirest
                .post("https://www.google.com/recaptcha/api/siteverify")
                .field("secret", App.getConfig().getString("captcha.secret"))
                .field("response", cc.getToken())
                //.field("remoteip", "null")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> response) {
                        JsonNode body = response.getBody();

                        String hostname = App.getConfig().getString("host");

                        boolean success = body.getObject().getBoolean("success") && body.getObject().getString("hostname").equals(hostname);
                        if (success) {
                            user.validateCaptcha();
                        }

                        server.send(channel, new ServerCaptchaStatus(success));
                    }

                    @Override
                    public void failed(UnirestException e) {

                    }

                    @Override
                    public void cancelled() {

                    }
                });
    }

    public void updateUserData() {
        int userCount = App.getServer().getNonIdledUsersCount();
        if (previousUserCount != userCount) {
            previousUserCount = userCount;
            server.broadcast(new ServerUsers(userCount));
        }
    }

    private void sendPlacementOverrides(WebSocketChannel channel, User user) {
        server.send(channel, new ServerAdminPlacementOverrides(user.getPlaceOverrides()));
    }

    public void sendPlacementOverrides(User user) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendPlacementOverrides(ch, user);
        }
    }

    private void sendCooldownData(WebSocketChannel channel, User user) {
        server.send(channel, new ServerCooldown(user.getRemainingCooldown()));
    }

    private void sendCooldownData(User user) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendCooldownData(ch, user);
        }
    }

    private void broadcastPixelUpdate(int x, int y, int color) {
        server.broadcast(new ServerPlace(Collections.singleton(new ServerPlace.Pixel(x, y, color))));
    }

    public void sendAvailablePixels(WebSocketChannel ch, User user, String cause) {
        server.send(ch, new ServerPixels(user.getAvailablePixels(cause.equals("override")), cause));
    }
    public void sendAvailablePixels(User user, String cause) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendAvailablePixels(ch, user, cause);
        }
    }

    public void sendPixelCountUpdate(User user) {
        for (WebSocketChannel ch : user.getConnections()) {
            server.send(ch, new ServerPixelCountUpdate(user));
        }
    }

    private void ackUndo(User user, int x, int y) {
        ack(user, "UNDO", x, y);
    }

    private void ackPlace(User user, int x, int y) {
        ack(user, "PLACE", x, y);
    }

    private void ack(User user, String _for, int x, int y) {
        for (WebSocketChannel ch : user.getConnections()) {
            server.send(ch, new ServerACK(_for, x, y));
        }
    }

    public int getNumAllCons() {
        return numAllCons;
    }
}
