package com.example.tpadialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public final class TpaDialog extends JavaPlugin implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private enum Type {
        TPA, TPAHERE;

        String label() {
            return this == TPA ? "TPA" : "TPAHere";
        }

        String hint() {
            return this == TPA ? "Teleport yourself to a player" : "Bring a player to you";
        }
    }

    private record Request(UUID from, UUID to, Type type, long expires) {
    }

    private final List<Request> requests = new ArrayList<>();
    private long timeoutMs;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        timeoutMs = getConfig().getLong("request-timeout-seconds", 60) * 1000L;

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("tpa")).setExecutor(this);

        // Purge expired requests and tell the sender
        Bukkit.getScheduler().runTaskTimer(this, this::purgeExpired, 100L, 100L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        openMain(p);
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        requests.removeIf(r -> r.from().equals(id) || r.to().equals(id));
    }

    // ------------------------------------------------------------------ request storage

    private @Nullable Request find(UUID from, UUID to, Type type) {
        long now = System.currentTimeMillis();
        for (Request r : requests) {
            if (r.from().equals(from) && r.to().equals(to) && r.type() == type && r.expires() > now) return r;
        }
        return null;
    }

    private long countIncoming(UUID to, @Nullable Type type) {
        long now = System.currentTimeMillis();
        return requests.stream()
                .filter(r -> r.to().equals(to) && r.expires() > now && (type == null || r.type() == type))
                .count();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            Request r = it.next();
            if (r.expires() > now) continue;
            it.remove();
            Player from = Bukkit.getPlayer(r.from());
            Player to = Bukkit.getPlayer(r.to());
            if (from != null) {
                from.sendMessage(MM.deserialize("<gray>Your " + r.type().label() + " request to <white>"
                        + (to != null ? to.getName() : "a player") + " <gray>expired."));
            }
        }
    }

    // ------------------------------------------------------------------ dialog helpers

    private ActionButton button(Component label, @Nullable Component tooltip, int width, Consumer<Player> onClick) {
        return ActionButton.create(label, tooltip, width,
                DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p) {
                        // make sure we always run on the main thread
                        Bukkit.getScheduler().runTask(this, () -> onClick.accept(p));
                    }
                }, CLICK));
    }

    private void show(Player p, Component title, List<Component> body, List<ActionButton> actions,
                      @Nullable ActionButton exit, int columns) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component c : body) bodies.add(DialogBody.plainMessage(c));

        List<ActionButton> acts = actions;
        ActionButton exitBtn = exit;
        if (acts.isEmpty() && exitBtn != null) {
            acts = List.of(exitBtn);
            exitBtn = null;
        }

        final List<ActionButton> finalActs = acts;
        final ActionButton finalExit = exitBtn;
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title).body(bodies).build())
                .type(DialogType.multiAction(finalActs, finalExit, columns)));
        p.showDialog(dialog);
    }

    private ActionButton backButton(Consumer<Player> target) {
        return button(MM.deserialize("<gray>Back"), null, 100, target);
    }

    // ------------------------------------------------------------------ dialogs

    /** Screen 1: choose TPA or TPAHere. */
    private void openMain(Player p) {
        long total = countIncoming(p.getUniqueId(), null);
        List<Component> body = new ArrayList<>();
        body.add(MM.deserialize("<gray>Choose what kind of teleport request you want to use."));
        if (total > 0) body.add(MM.deserialize("<yellow>You have " + total + " pending request(s)."));

        List<ActionButton> actions = new ArrayList<>();
        for (Type t : Type.values()) {
            long n = countIncoming(p.getUniqueId(), t);
            String suffix = n > 0 ? " <yellow>(" + n + ")" : "";
            actions.add(button(MM.deserialize("<green>" + t.label() + suffix),
                    MM.deserialize("<gray>" + t.hint()), 200, pl -> openList(pl, t)));
        }

        ActionButton close = ActionButton.create(MM.deserialize("<gray>Close"), null, 100, null);
        show(p, MM.deserialize("<gold><bold>Teleport"), body, actions, close, 1);
    }

    /** Screen 2: pick a player. Players who sent you a request of this type are listed first. */
    private void openList(Player p, Type type) {
        List<Player> others = new ArrayList<>(Bukkit.getOnlinePlayers());
        others.remove(p);
        others.sort(Comparator
                .comparing((Player o) -> find(o.getUniqueId(), p.getUniqueId(), type) == null)
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        List<Component> body = new ArrayList<>();
        body.add(MM.deserialize(others.isEmpty()
                ? "<red>There are no other players online."
                : "<gray>Select a player. <green>●<gray> means they sent you a request."));

        List<ActionButton> actions = new ArrayList<>();
        for (Player o : others) {
            UUID id = o.getUniqueId();
            boolean incoming = find(id, p.getUniqueId(), type) != null;
            Component label = incoming
                    ? MM.deserialize("<green>● <white>" + o.getName())
                    : MM.deserialize("<white>" + o.getName());
            Component tip = MM.deserialize(incoming
                    ? "<gray>Sent you a request - click to accept or deny"
                    : "<gray>Click to send a " + type.label() + " request");
            actions.add(button(label, tip, 150, pl -> openTarget(pl, type, id)));
        }

        show(p, MM.deserialize("<gold><bold>" + type.label()), body, actions, backButton(this::openMain), 2);
    }

    /** Screen 3: send a request, or accept/deny one that was sent to you. */
    private void openTarget(Player p, Type type, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            p.sendMessage(MM.deserialize("<red>That player is no longer online."));
            openList(p, type);
            return;
        }

        Request incoming = find(targetId, p.getUniqueId(), type);
        Request outgoing = find(p.getUniqueId(), targetId, type);
        Component title = MM.deserialize("<gold><bold>" + type.label() + " <gray>- <white>" + target.getName());

        List<Component> body = new ArrayList<>();
        List<ActionButton> actions = new ArrayList<>();
        int columns = 1;

        if (incoming != null) {
            if (type == Type.TPA) {
                body.add(MM.deserialize("<white>" + target.getName() + " <yellow>wants to teleport to you."));
                body.add(MM.deserialize("<gray>If you accept, <white>" + target.getName()
                        + "<gray> will be teleported to you."));
            } else {
                body.add(MM.deserialize("<white>" + target.getName() + " <yellow>wants you to teleport to them."));
                body.add(MM.deserialize("<gray>If you accept, you will be teleported to <white>"
                        + target.getName() + "<gray>."));
            }
            actions.add(button(MM.deserialize("<green>Accept"), MM.deserialize("<gray>Accept the request"), 120,
                    pl -> accept(pl, targetId, type)));
            actions.add(button(MM.deserialize("<red>Deny"), MM.deserialize("<gray>Deny the request"), 120,
                    pl -> deny(pl, targetId, type)));
            columns = 2;
        } else if (outgoing != null) {
            body.add(MM.deserialize("<yellow>You already sent a " + type.label() + " request to <white>"
                    + target.getName() + "<yellow>. Waiting for a reply..."));
            actions.add(button(MM.deserialize("<red>Cancel request"), null, 200, pl -> {
                requests.remove(outgoing);
                pl.sendMessage(MM.deserialize("<gray>Request cancelled."));
            }));
        } else {
            body.add(MM.deserialize("<gray>" + (type == Type.TPA
                    ? "If <white>" + target.getName() + "<gray> accepts, you will be teleported to them."
                    : "If <white>" + target.getName() + "<gray> accepts, they will be teleported to you.")));
            actions.add(button(MM.deserialize("<green>Send " + type.label() + " request"), null, 200,
                    pl -> sendRequest(pl, targetId, type)));
        }

        show(p, title, body, actions, backButton(pl -> openList(pl, type)), columns);
    }

    // ------------------------------------------------------------------ actions

    private void sendRequest(Player from, UUID toId, Type type) {
        Player to = Bukkit.getPlayer(toId);
        if (to == null) {
            from.sendMessage(MM.deserialize("<red>That player is no longer online."));
            return;
        }
        if (to.equals(from)) return;

        requests.removeIf(r -> r.from().equals(from.getUniqueId()) && r.to().equals(toId) && r.type() == type);
        requests.add(new Request(from.getUniqueId(), toId, type, System.currentTimeMillis() + timeoutMs));

        from.sendMessage(MM.deserialize("<green>" + type.label() + " request sent to <white>" + to.getName()
                + "<green>. It expires in " + (timeoutMs / 1000) + "s."));

        String what = type == Type.TPA ? "wants to teleport to you." : "wants you to teleport to them.";
        to.sendMessage(MM.deserialize("<gold>" + from.getName() + " <yellow>" + what
                        + " <gray>[<green><bold>Click to respond<reset><gray>]")
                .clickEvent(ClickEvent.runCommand("/tpa"))
                .hoverEvent(HoverEvent.showText(MM.deserialize("<gray>Opens the /tpa menu"))));
    }

    private void accept(Player me, UUID fromId, Type type) {
        Request r = find(fromId, me.getUniqueId(), type);
        if (r == null) {
            me.sendMessage(MM.deserialize("<red>That request has expired."));
            return;
        }
        requests.remove(r);

        Player from = Bukkit.getPlayer(fromId);
        if (from == null) {
            me.sendMessage(MM.deserialize("<red>That player is no longer online."));
            return;
        }

        Player mover = type == Type.TPA ? from : me;
        Player dest = type == Type.TPA ? me : from;

        from.sendMessage(MM.deserialize("<green>" + me.getName() + " accepted your " + type.label() + " request."));
        me.sendMessage(MM.deserialize("<green>Request accepted."));

        // COMMAND cause so combat plugins (e.g. BountyCombat) can still block it
        mover.teleportAsync(dest.getLocation(), TeleportCause.COMMAND).thenAccept(ok -> {
            if (ok) {
                mover.sendMessage(MM.deserialize("<green>Teleported to <white>" + dest.getName() + "<green>."));
            } else {
                mover.sendMessage(MM.deserialize("<red>The teleport was cancelled."));
                dest.sendMessage(MM.deserialize("<red>The teleport of <white>" + mover.getName()
                        + " <red>was cancelled."));
            }
        });
    }

    private void deny(Player me, UUID fromId, Type type) {
        Request r = find(fromId, me.getUniqueId(), type);
        if (r == null) {
            me.sendMessage(MM.deserialize("<red>That request has expired."));
            return;
        }
        requests.remove(r);
        me.sendMessage(MM.deserialize("<gray>Request denied."));
        Player from = Bukkit.getPlayer(fromId);
        if (from != null) {
            from.sendMessage(MM.deserialize("<red>" + me.getName() + " denied your " + type.label() + " request."));
        }
    }
}
