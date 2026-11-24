package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What your crosshair is on, within your reach: for a block its icon, name and mod, whether the item in your hand can
 * harvest it, how long it takes to break with that item, and the light level on the face you look at. For an entity
 * only its name and type: never health or anything else, and a player's name only when the game would show their
 * name tag to you. Nothing past reach and nothing behind blocks: it reads the same target vanilla's crosshair uses.
 */
public class LookInfoHud extends TextHudModule {
    private final BooleanSetting showIcon = add(new BooleanSetting("Show Icon", true)
            .describe("The block's item (or the mob's spawn egg) in front of the text."));
    private final BooleanSetting showMod = add(new BooleanSetting("Show Mod", true)
            .describe("Which game or mod the block or entity comes from (minecraft, or a mod's id)."));
    private final BooleanSetting showTool = add(new BooleanSetting("Show Tool", true)
            .describe("Whether the item in your hand can harvest the block, or which tool it needs."));
    private final BooleanSetting showBreakTime = add(new BooleanSetting("Show Break Time", true)
            .describe("How long the block takes to break with the item in your hand, as you are now."));
    private final BooleanSetting showLight = add(new BooleanSetting("Show Light", true)
            .describe("Block light and sky light on the face you're looking at (0 block light lets monsters spawn)."));
    private final BooleanSetting showEntities = add(new BooleanSetting("Show Entities", true)
            .describe("Also show the name and type of a mob or player in reach. Never their health."));

    private static final int GOOD = 0xFF3BE37A, BAD = 0xFFFF4D5E, DIM = 0xFF9B9BA6;

    /** What each line is, so it can be coloured: 0 name, 1 dim, 2 good, 3 bad. */
    private final List<Integer> kinds = new ArrayList<>();
    private ItemStack icon = ItemStack.EMPTY;

    public LookInfoHud() {
        super("Look Info", "The block or mob at your crosshair: name, mod, right tool, break time and light.", 4, 120);
    }

    @Override
    protected List<String> lines(boolean preview) {
        kinds.clear();
        icon = ItemStack.EMPTY;
        List<String> out = new ArrayList<>();
        HitResult hit = mc.crosshairTarget;
        PlayerEntity p = mc.player;
        if (p != null && mc.world != null && hit != null) {
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) block(out, p, bh);
            else if (hit instanceof EntityHitResult eh && hit.getType() == HitResult.Type.ENTITY && showEntities.get()) entity(out, p, eh.getEntity());
        }
        if (out.isEmpty() && preview) {
            icon = new ItemStack(Items.OAK_LOG);
            add(out, "Oak Log", 0);
            if (showMod.get()) add(out, "minecraft", 1);
            if (showTool.get()) add(out, "✔ Any tool works", 2);
            if (showBreakTime.get()) add(out, "Breaks in 3.00 s", 1);
            if (showLight.get()) add(out, "Light 0 · Sky 15", 1);
        }
        return out;
    }

    private void add(List<String> out, String line, int kind) {
        out.add(line);
        kinds.add(kind);
    }

    private void block(List<String> out, PlayerEntity p, BlockHitResult bh) {
        BlockPos pos = bh.getBlockPos();
        // Within reach only (the crosshair target already is; this also covers a stale target after moving away).
        if (p.getEyePos().squaredDistanceTo(bh.getPos()) > sq(p.getBlockInteractionRange() + 0.5)) return;
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return;
        icon = new ItemStack(state.getBlock().asItem());
        add(out, state.getBlock().getName().getString(), 0);
        if (showMod.get()) add(out, Registries.BLOCK.getId(state.getBlock()).getNamespace(), 1);
        ItemStack hand = p.getMainHandStack();
        float hardness = state.getHardness(mc.world, pos);
        if (showTool.get() && hardness >= 0) {
            boolean suitable = hand.isSuitableFor(state);
            String need = toolFor(state);
            if (state.isToolRequired() && !suitable) add(out, "✘ Needs " + (need == null ? "a better tool" : need), 3);
            else if (need == null) add(out, "✔ Any tool works", 2);
            else if (suitable || hand.getMiningSpeedMultiplier(state) > 1f) add(out, "✔ Right tool", 2);
            else add(out, "✔ Faster with " + need, 1);
        }
        if (showBreakTime.get()) {
            if (hardness < 0) {
                add(out, "Unbreakable", 3);
            } else {
                float delta = state.calcBlockBreakingDelta(p, mc.world, pos);
                if (delta >= 1f) add(out, "Breaks instantly", 1);
                else if (delta <= 0f) add(out, "Can't be broken", 3);
                else add(out, String.format(Locale.ROOT, "Breaks in %.2f s", Math.ceil(1f / delta) / 20.0), 1);
            }
        }
        if (showLight.get()) {
            BlockPos face = pos.offset(bh.getSide());
            int block = mc.world.getLightLevel(LightType.BLOCK, face), sky = mc.world.getLightLevel(LightType.SKY, face);
            add(out, "Light " + block + " · Sky " + sky, block == 0 ? 3 : 1);
        }
    }

    /** The kind of tool a block is mined with ("a pickaxe"), or null when no tool type speeds it up. */
    private static String toolFor(BlockState state) {
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) return "a pickaxe";
        if (state.isIn(BlockTags.AXE_MINEABLE)) return "an axe";
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) return "a shovel";
        if (state.isIn(BlockTags.HOE_MINEABLE)) return "a hoe";
        return null;
    }

    private void entity(List<String> out, PlayerEntity me, Entity e) {
        if (e == null || e.isInvisibleTo(me)) return;
        if (me.getEyePos().squaredDistanceTo(e.getBoundingBox().getCenter()) > sq(me.getEntityInteractionRange() + 2)) return;
        if (e instanceof PlayerEntity other) {
            add(out, nameTagShown(me, other) ? other.getDisplayName().getString() : "Player", 0);
            add(out, "Player", 1);
            return;
        }
        add(out, e.getDisplayName().getString(), 0);
        String type = Registries.ENTITY_TYPE.getId(e.getType()).toString();
        add(out, showMod.get() ? type : e.getType().getName().getString(), 1);
        SpawnEggItem egg = SpawnEggItem.forEntity(e.getType());
        if (egg != null) icon = new ItemStack(egg);
    }

    /** Whether vanilla would show {@code other}'s name tag to {@code me}, following their team's rule. */
    private static boolean nameTagShown(PlayerEntity me, PlayerEntity other) {
        AbstractTeam team = other.getScoreboardTeam();
        if (team == null) return true;
        AbstractTeam mine = me.getScoreboardTeam();
        return switch (team.getNameTagVisibilityRule()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> mine != null && team.isEqual(mine);
            case HIDE_FOR_OWN_TEAM -> mine == null || !team.isEqual(mine);
        };
    }

    private static double sq(double d) { return d * d; }

    @Override
    protected int lineColor(int index, int base) {
        if (index >= kinds.size()) return base;
        return switch (kinds.get(index)) {
            case 1 -> DIM;
            case 2 -> GOOD;
            case 3 -> BAD;
            default -> base;
        };
    }

    @Override
    protected int leadingWidth() { return showIcon.get() && !icon.isEmpty() ? 20 : 0; }

    @Override
    protected int minHeight() { return showIcon.get() && !icon.isEmpty() ? 20 : 0; }

    @Override
    protected void drawLeading(DrawContext ctx, int x, int y, int h) {
        if (showIcon.get() && !icon.isEmpty()) ctx.drawItem(icon, x + 1, y + Math.max(0, (h - 16) / 2));
    }
}
