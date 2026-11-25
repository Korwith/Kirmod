package net.minecraft.src;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Kirmod > Music... (1.36): picks the soundtrack KirMusic plays, built like the texture pack screen.
 *
 * On the left, Now Playing: the song's cover, title, soundtrack and time, with buttons for the song before,
 * play/pause, the next song and stop, and how long until the next song when none plays. On the right, in a
 * list that scrolls:
 * - "Soundtracks": a tile per soundtrack (Minecraft 1.3.2, each folder in .minecraft/soundtracks, All Music);
 *   click one to use it;
 * - "Options": Order, Music Frequency, Music Pitch, Music Discs, Sound Test, 0x10c, Nether Soundtrack and
 *   End Soundtrack. A click moves to the next value, a right-click (or shift-click) back;
 * - "Playlist": the songs that play (and the Nether's and End's, when they have their own); click one to
 *   play it now. Songs still converting from MP3 etc. are greyed out;
 * - "Credits": C418's, and any credits.txt of the soundtracks in use.
 * At the bottom, "Open Soundtracks Folder" and "Done". Folders dropped into the folder show up while the
 * screen is open.
 */
public class GuiKirmodMusic extends GuiScreen
{
    private static final int FOLDER_ID = 1;
    private static final int DONE_ID = 2;
    private static final int PREV_ID = 3;
    private static final int PLAY_ID = 4;
    private static final int NEXT_ID = 5;
    private static final int STOP_ID = 6;

    private static final int TILE_W = 74;
    private static final int TILE_H = 82;
    private static final int ICON = 48;
    private static final int GAP = 6;
    private static final int SCROLLBAR_W = 6;
    private static final int OPTION_H = 16;
    private static final int TRACK_H = 11;

    private static final int COLOR_GOLD = 0xFFD860;
    private static final int COLOR_GRAY = 0xA0A0A0;
    private static final int COLOR_DIM = 0x707070;
    private static final int COLOR_OK = 0x80FF80;

    private final GuiScreen parentScreen;
    private final List items = new ArrayList();

    private int previewLeft;
    private int previewRight;
    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private float scroll;
    private boolean draggingScroll;
    private int ticks;
    private int libraryStamp;

    private GuiButton playButton;

    private String status;
    private int statusColor;
    private long statusUntil;

    /** One thing in the scrolling list: a header, a tile, an option row, a track row or a text line. */
    private static class Row
    {
        static final int HEADER = 0;
        static final int TILE = 1;
        static final int OPTION = 2;
        static final int TRACK = 3;
        static final int TEXT = 4;
        static final int SUBHEADER = 5;

        final int type;
        final int x;
        final int y;
        final int w;
        final int h;
        final String text;
        final Object target;
        final List lines;

        Row(int type, int x, int y, int w, int h, String text, Object target, List lines)
        {
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
            this.target = target;
            this.lines = lines;
        }
    }

    public GuiKirmodMusic(GuiScreen parent)
    {
        this.parentScreen = parent;
    }

    public void initGui()
    {
        Keyboard.enableRepeatEvents(true);
        this.controlList.clear();
        int var1 = this.width >= 420 ? 130 : 104;
        this.previewLeft = 8;
        this.previewRight = this.previewLeft + var1;
        this.listLeft = this.previewRight + 6;
        this.listRight = this.width - 8;
        this.listTop = 32;
        this.listBottom = this.height - 32;
        this.controlList.add(new GuiButton(FOLDER_ID, this.width / 2 - 154, this.height - 26, 150, 20, "Open Soundtracks Folder"));
        this.controlList.add(new GuiButton(DONE_ID, this.width / 2 + 4, this.height - 26, 150, 20, StringTranslate.getInstance().translateKey("gui.done")));
        int var2 = (var1 - 6) / 4;
        int var3 = this.previewLeft + (var1 - var2 * 4 - 6) / 2 + 1;
        int var4 = this.listBottom - 24;
        // The font has no media symbols, so the buttons are blank and drawIcons() draws them.
        this.controlList.add(new GuiButton(PREV_ID, var3, var4, var2, 20, ""));
        this.playButton = new GuiButton(PLAY_ID, var3 + (var2 + 2), var4, var2, 20, "");
        this.controlList.add(this.playButton);
        this.controlList.add(new GuiButton(NEXT_ID, var3 + (var2 + 2) * 2, var4, var2, 20, ""));
        this.controlList.add(new GuiButton(STOP_ID, var3 + (var2 + 2) * 3, var4, var2, 20, ""));
        KirMusic.refresh(this.mc, true);
        this.libraryStamp = KirMusic.getSoundtracks(this.mc).hashCode();
        this.build();
        ArrayList var5 = new ArrayList(KirMusic.getMusicPlaylist());
        var5.addAll(KirMusic.getSelected() != null ? KirMusic.getSelected().tracks : new ArrayList());
        KirMusic.readDurations(var5);
    }

    /* ------------------------------------------------------------------ Layout */

    private int innerWidth()
    {
        return this.listRight - this.listLeft - SCROLLBAR_W;
    }

    private void build()
    {
        this.items.clear();
        int var1 = this.innerWidth();
        int var2 = 4;
        List var3 = KirMusic.getSoundtracks(this.mc);
        boolean var4 = var3.size() <= 2;
        var2 = this.addHeader("Soundtracks", var4 ? "Put album folders (MP3, OGG, FLAC...) in .minecraft/soundtracks to see them here: Volume Alpha, Volume Beta, Sound Test, 0x10c or your own." : null, var2);
        int var5 = Math.max(1, (var1 - 8 + GAP) / (TILE_W + GAP));
        int var6 = (var1 - (var5 * (TILE_W + GAP) - GAP)) / 2;

        for (int var7 = 0; var7 < var3.size(); ++var7)
        {
            KirMusic.Soundtrack var8 = (KirMusic.Soundtrack)var3.get(var7);
            List var9 = this.fontRenderer.listFormattedStringToWidth(var8.name, TILE_W - 4);

            if (var9.size() > 2)
            {
                String var10 = this.fontRenderer.trimStringToWidth((String)var9.get(1), TILE_W - 4 - this.fontRenderer.getStringWidth(".."));
                var9 = new ArrayList(var9.subList(0, 1));
                var9.add(var10 + "..");
            }

            this.items.add(new Row(Row.TILE, var6 + var7 % var5 * (TILE_W + GAP), var2 + var7 / var5 * (TILE_H + GAP), TILE_W, TILE_H, var8.name, var8, var9));
        }

        int var11 = (var3.size() + var5 - 1) / var5;
        var2 += var11 * (TILE_H + GAP) - GAP + 8;
        var2 = this.addHeader("Options", (String)null, var2);
        KirModule[] var12 = {mod_Kirmod.MUSIC_ORDER, mod_Kirmod.MUSIC_FREQUENCY, mod_Kirmod.MUSIC_PITCH, mod_Kirmod.MUSIC_DISCS, mod_Kirmod.MUSIC_SOUND_TEST, mod_Kirmod.MUSIC_0X10C, mod_Kirmod.MUSIC_NETHER, mod_Kirmod.MUSIC_END};
        ArrayList var13 = new ArrayList();

        for (int var14 = 0; var14 < var12.length; ++var14)
        {
            if (var12[var14].isEnabled())
            {
                var13.add(var12[var14]);
            }
        }

        int var15 = var1 - 16 >= 2 * 130 + 4 ? 2 : 1;
        int var16 = var15 == 2 ? (var1 - 16 - 4) / 2 : var1 - 16;
        int var17 = (var13.size() + var15 - 1) / var15;

        for (int var18 = 0; var18 < var13.size(); ++var18)
        {
            int var19 = var18 / var17;
            int var20 = var18 % var17;
            this.items.add(new Row(Row.OPTION, 8 + var19 * (var16 + 4), var2 + var20 * (OPTION_H + 2), var16, OPTION_H, (String)null, var13.get(var18), (List)null));
        }

        var2 += var17 * (OPTION_H + 2) + 8;
        var2 = this.addHeader("Playlist", (String)null, var2);
        List var21 = KirMusic.getMusicPlaylist();
        KirMusic.Soundtrack var22 = KirMusic.getSelected();
        var2 = this.addTracks(var21, var2, var22 == null || var22.kind == KirMusic.KIND_ALL || var21.size() > 0 && this.mixed(var21));

        if (KirMusic.getPlaylistKind(-1) == 1)
        {
            var2 = this.addSubheader("In the Nether", var2);
            var2 = this.addTracks(KirMusic.getDimensionTracks(KirMusic.ROLE_NETHER), var2, true);
        }

        if (KirMusic.getPlaylistKind(1) == 2)
        {
            var2 = this.addSubheader("In the End", var2);
            var2 = this.addTracks(KirMusic.getDimensionTracks(KirMusic.ROLE_END), var2, true);
        }

        var2 += 8;
        var2 = this.addHeader("Credits", (String)null, var2);
        ArrayList var23 = new ArrayList();
        var23.add("§f" + KirMusic.C418_CREDIT + "§7: Minecraft - Volume Alpha (2011), Minecraft - Volume Beta (2013), 0x10c (2012) and the music and music discs of Minecraft 1.3.2.");
        ArrayList var24 = new ArrayList();

        if (var22 != null)
        {
            var24.add(var22);
        }

        for (int var25 = 0; var25 < var21.size(); ++var25)
        {
            KirMusic.Soundtrack var26 = ((KirMusic.Track)var21.get(var25)).soundtrack;

            if (!var24.contains(var26))
            {
                var24.add(var26);
            }
        }

        for (int var27 = 0; var27 < var24.size(); ++var27)
        {
            KirMusic.Soundtrack var28 = (KirMusic.Soundtrack)var24.get(var27);

            for (int var29 = 0; var29 < var28.credits.size(); ++var29)
            {
                String var30 = (String)var28.credits.get(var29);

                if (!var30.equals(KirMusic.C418_CREDIT))
                {
                    var23.add("§7" + var28.name + ": " + var30);
                }
            }
        }

        var23.add("§7Kirmod plays your own copies of these albums from .minecraft/soundtracks; it does not include them.");

        for (int var31 = 0; var31 < var23.size(); ++var31)
        {
            List var32 = this.fontRenderer.listFormattedStringToWidth((String)var23.get(var31), var1 - 16);

            for (int var33 = 0; var33 < var32.size(); ++var33)
            {
                this.items.add(new Row(Row.TEXT, 8, var2, var1 - 16, 10, (String)var32.get(var33), (Object)null, (List)null));
                var2 += 10;
            }

            var2 += 2;
        }

        this.contentHeight = var2 + 4;
        this.scrollBy(0.0F);
    }

    private boolean mixed(List tracks)
    {
        Object var2 = ((KirMusic.Track)tracks.get(0)).soundtrack;

        for (int var3 = 1; var3 < tracks.size(); ++var3)
        {
            if (((KirMusic.Track)tracks.get(var3)).soundtrack != var2)
            {
                return true;
            }
        }

        return false;
    }

    private int addHeader(String title, String hint, int y)
    {
        List var4 = hint == null ? null : this.fontRenderer.listFormattedStringToWidth(hint, this.innerWidth() - 16);
        this.items.add(new Row(Row.HEADER, 8, y, this.innerWidth() - 16, 14, title, (Object)null, var4));
        return y + 16 + (var4 == null ? 0 : var4.size() * 10 + 2);
    }

    private int addSubheader(String title, int y)
    {
        this.items.add(new Row(Row.SUBHEADER, 8, y + 2, this.innerWidth() - 16, 12, title, (Object)null, (List)null));
        return y + 14;
    }

    private int addTracks(List tracks, int y, boolean showAlbum)
    {
        if (tracks.isEmpty())
        {
            this.items.add(new Row(Row.TEXT, 8, y, this.innerWidth() - 16, 10, "§7No songs. Pick a soundtrack above, or check its folder.", (Object)null, (List)null));
            return y + 12;
        }

        for (int var4 = 0; var4 < tracks.size(); ++var4)
        {
            this.items.add(new Row(Row.TRACK, 8, y, this.innerWidth() - 16, TRACK_H, showAlbum ? "album" : null, tracks.get(var4), (List)null));
            y += TRACK_H;
        }

        return y + 2;
    }

    /* ------------------------------------------------------------------ Input */

    protected void actionPerformed(GuiButton button)
    {
        if (!button.enabled)
        {
            return;
        }

        switch (button.id)
        {
            case FOLDER_ID:
                KirMusic.openDir();
                break;

            case DONE_ID:
                this.mc.displayGuiScreen(this.parentScreen);
                break;

            case PREV_ID:
                KirMusic.playPrevious(this.mc);
                break;

            case PLAY_ID:
                KirMusic.togglePause(this.mc);
                break;

            case NEXT_ID:
                KirMusic.playNext(this.mc, true);
                break;

            case STOP_ID:
                KirMusic.stop(this.mc);
        }
    }

    protected void keyTyped(char par1, int par2)
    {
        if (par2 == Keyboard.KEY_ESCAPE)
        {
            this.mc.displayGuiScreen(this.parentScreen);
        }
        else if (par2 == Keyboard.KEY_SPACE)
        {
            KirMusic.togglePause(this.mc);
        }
    }

    protected void mouseClicked(int x, int y, int button)
    {
        super.mouseClicked(x, y, button);

        if (button == 0 && this.hasScrollbar() && x >= this.listRight - SCROLLBAR_W && x < this.listRight && y >= this.listTop && y < this.listBottom)
        {
            this.draggingScroll = true;
            this.dragScrollTo(y);
            return;
        }

        Row var4 = this.getItemAt(x, y);

        if (var4 == null || button > 1)
        {
            return;
        }

        boolean var5 = button == 1 || isShiftKeyDown();

        if (var4.type == Row.TILE && button == 0)
        {
            this.playClick();
            KirMusic.Soundtrack var6 = (KirMusic.Soundtrack)var4.target;

            if (var6 != KirMusic.getSelected())
            {
                KirMusic.select(var6);
                this.build();
                this.setStatus("Now using " + var6.name + (KirMusic.getCurrent() != null ? ". Press >| to hear it now" : ""), COLOR_OK);
                KirMusic.readDurations(var6.tracks);
            }
        }
        else if (var4.type == Row.OPTION)
        {
            this.playClick();
            KirModule var7 = (KirModule)var4.target;
            int var8 = var7.getValueNames().length;
            var7.setValue((var7.getValue() + (var5 ? var8 - 1 : 1)) % var8);
            this.build();
        }
        else if (var4.type == Row.TRACK && button == 0)
        {
            this.playClick();
            KirMusic.Track var9 = (KirMusic.Track)var4.target;

            if (KirMusic.play(this.mc, var9))
            {
                this.setStatus("Playing " + var9.title, COLOR_OK);
            }
        }
    }

    private void playClick()
    {
        this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
    }

    public void handleMouseInput()
    {
        super.handleMouseInput();
        int var1 = Mouse.getEventDWheel();

        if (var1 != 0)
        {
            this.scrollBy(var1 > 0 ? -30.0F : 30.0F);
        }
    }

    private void scrollBy(float amount)
    {
        float var2 = (float)Math.max(0, this.contentHeight - (this.listBottom - this.listTop));
        this.scroll = Math.max(0.0F, Math.min(var2, this.scroll + amount));
    }

    private boolean hasScrollbar()
    {
        return this.contentHeight > this.listBottom - this.listTop;
    }

    private void dragScrollTo(int mouseY)
    {
        int var2 = this.listBottom - this.listTop;
        int var3 = this.getThumbHeight();
        float var4 = (float)(mouseY - this.listTop - var3 / 2) / (float)Math.max(1, var2 - var3);
        this.scroll = 0.0F;
        this.scrollBy(var4 * (float)(this.contentHeight - var2));
    }

    private int getThumbHeight()
    {
        int var1 = this.listBottom - this.listTop;
        return Math.max(16, var1 * var1 / Math.max(1, this.contentHeight));
    }

    private Row getItemAt(int mouseX, int mouseY)
    {
        if (mouseX < this.listLeft || mouseX >= this.listRight - SCROLLBAR_W || mouseY < this.listTop || mouseY >= this.listBottom)
        {
            return null;
        }

        for (int var3 = 0; var3 < this.items.size(); ++var3)
        {
            Row var4 = (Row)this.items.get(var3);

            if (var4.type != Row.TILE && var4.type != Row.OPTION && var4.type != Row.TRACK)
            {
                continue;
            }

            int var5 = this.listLeft + var4.x;
            int var6 = this.screenY(var4);

            if (mouseX >= var5 && mouseX < var5 + var4.w && mouseY >= var6 && mouseY < var6 + var4.h)
            {
                return var4;
            }
        }

        return null;
    }

    private int screenY(Row item)
    {
        return this.listTop + item.y - (int)this.scroll;
    }

    private void setStatus(String text, int color)
    {
        this.status = text;
        this.statusColor = color;
        this.statusUntil = System.currentTimeMillis() + 5000L;
    }

    public void updateScreen()
    {
        // Folders dropped in while the screen is open, and conversions that finish, show up.
        if (++this.ticks % 10 == 0)
        {
            KirMusic.refresh(this.mc, false);
            int var1 = KirMusic.getSoundtracks(this.mc).hashCode();

            if (var1 != this.libraryStamp)
            {
                this.libraryStamp = var1;
                this.build();
            }
        }

        String var2 = KirMusic.getMessage();

        if (var2 != null && !var2.equals(this.status))
        {
            this.setStatus(var2, 0xFF8060);
        }
    }

    public void onGuiClosed()
    {
        Keyboard.enableRepeatEvents(false);
    }

    /* ------------------------------------------------------------------ Drawing */

    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        if (this.draggingScroll)
        {
            if (Mouse.isButtonDown(0))
            {
                this.dragScrollTo(mouseY);
            }
            else
            {
                this.draggingScroll = false;
            }
        }

        KirMusic.Track var4 = KirMusic.getCurrent();
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "Music", this.width / 2, 8, 0xFFFFFF);

        if (this.status != null && System.currentTimeMillis() < this.statusUntil)
        {
            this.drawCenteredString(this.fontRenderer, this.status, this.width / 2, 19, this.statusColor);
        }
        else
        {
            this.drawCenteredString(this.fontRenderer, "Click a soundtrack to use it, or a song to play it now", this.width / 2, 19, COLOR_DIM);
        }

        this.drawPanel(this.previewLeft, this.listTop, this.previewRight, this.listBottom);
        this.drawPanel(this.listLeft, this.listTop, this.listRight, this.listBottom);
        this.drawList(mouseX, mouseY);
        this.drawNowPlaying(var4);
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawIcons(var4 != null && !KirMusic.isPaused());
        Row var5 = this.getItemAt(mouseX, mouseY);

        if (var5 != null)
        {
            List var6 = this.getTooltip(var5);

            if (var6 != null && !var6.isEmpty())
            {
                this.drawTooltip(var6, mouseX, mouseY);
            }
        }
        else
        {
            for (int var7 = 0; var7 < this.controlList.size(); ++var7)
            {
                GuiButton var8 = (GuiButton)this.controlList.get(var7);

                if (var8.id >= PREV_ID && mouseX >= var8.xPosition && mouseY >= var8.yPosition && mouseX < var8.xPosition + var8.width && mouseY < var8.yPosition + 20)
                {
                    String[] var9 = {"Previous song (or back to the start of this one)", "Play / pause (Space)", "Next song", "Stop; the next song comes after Music Frequency's wait"};
                    ArrayList var10 = new ArrayList();
                    var10.add(var9[var8.id - PREV_ID]);
                    this.drawTooltip(var10, mouseX, mouseY);
                }
            }
        }
    }

    /** Previous, play or pause, next and stop symbols on the transport buttons, in white pixels. */
    private void drawIcons(boolean playing)
    {
        for (int var2 = 0; var2 < this.controlList.size(); ++var2)
        {
            GuiButton var3 = (GuiButton)this.controlList.get(var2);

            if (var3.id < PREV_ID)
            {
                continue;
            }

            int var4 = var3.xPosition + var3.width / 2;
            int var5 = var3.yPosition + 10;
            int var6 = var3.enabled ? 0xFFFFFFFF : 0xFFA0A0A0;

            switch (var3.id)
            {
                case PREV_ID:
                    drawRect(var4 - 4, var5 - 4, var4 - 2, var5 + 5, var6);
                    this.drawTriangle(var4 + 4, var5, -1, var6);
                    break;

                case PLAY_ID:
                    if (playing)
                    {
                        drawRect(var4 - 3, var5 - 4, var4 - 1, var5 + 5, var6);
                        drawRect(var4 + 1, var5 - 4, var4 + 3, var5 + 5, var6);
                    }
                    else
                    {
                        this.drawTriangle(var4 - 2, var5, 1, var6);
                    }

                    break;

                case NEXT_ID:
                    this.drawTriangle(var4 - 4, var5, 1, var6);
                    drawRect(var4 + 2, var5 - 4, var4 + 4, var5 + 5, var6);
                    break;

                case STOP_ID:
                    drawRect(var4 - 3, var5 - 3, var4 + 4, var5 + 4, var6);
            }
        }
    }

    /** A 5-pixel-deep triangle pointing right (dir 1) or left (dir -1) from its flat side at x, centred on y. */
    private void drawTriangle(int x, int y, int dir, int color)
    {
        for (int var5 = 0; var5 < 5; ++var5)
        {
            int var6 = x + dir * var5;
            drawRect(Math.min(var6, var6 + dir), y - 4 + var5, Math.max(var6, var6 + dir), y + 5 - var5, color);
        }
    }

    private List getTooltip(Row item)
    {
        ArrayList var2 = new ArrayList();

        if (item.type == Row.TILE)
        {
            KirMusic.Soundtrack var3 = (KirMusic.Soundtrack)item.target;
            var2.add("§e" + var3.name);

            if (!var3.fullName.equals(var3.name))
            {
                var2.add("§7" + var3.fullName);
            }

            StringBuilder var4 = new StringBuilder();
            this.count(var4, var3.count(KirMusic.ROLE_MUSIC), "song", "songs");
            this.count(var4, var3.count(KirMusic.ROLE_DISC), "music disc", "music discs");
            this.count(var4, var3.count(KirMusic.ROLE_NETHER), "Nether song", "Nether songs");
            this.count(var4, var3.count(KirMusic.ROLE_END), "End song", "End songs");
            var2.add(var4.length() == 0 ? "No songs" : var4.toString());

            for (int var5 = 0; var5 < var3.credits.size(); ++var5)
            {
                var2.addAll(this.fontRenderer.listFormattedStringToWidth("§7" + var3.credits.get(var5), 220));
            }
        }
        else if (item.type == Row.OPTION)
        {
            KirModule var6 = (KirModule)item.target;
            var2.add("§e" + var6.getTitle());
            String[] var7 = var6.getTooltip();

            if (var7 != null)
            {
                for (int var8 = 0; var8 < var7.length; ++var8)
                {
                    var2.addAll(this.fontRenderer.listFormattedStringToWidth(var7[var8], 220));
                }
            }

            if (this.isMissing(var6))
            {
                var2.add("§cNo such folder in .minecraft/soundtracks yet.");
            }
        }
        else if (item.type == Row.TRACK)
        {
            KirMusic.Track var9 = (KirMusic.Track)item.target;
            var2.add("§e" + var9.title);
            var2.add("§7" + var9.soundtrack.fullName + (var9.role == KirMusic.ROLE_DISC ? ", music disc" : (var9.role == KirMusic.ROLE_NETHER ? ", Nether" : (var9.role == KirMusic.ROLE_END ? ", End" : ""))));

            if (var9.source != null)
            {
                var2.addAll(this.fontRenderer.listFormattedStringToWidth("§8" + var9.source.getName(), 220));
            }
            else if (var9.vanillaFile != null && !var9.vanillaFile.equals(var9.title))
            {
                var2.add("§8" + var9.vanillaFile);
            }

            String var10 = this.stateText(var9);
            var2.add(var10 != null ? "§c" + var10 : "Click to play it now");
        }

        return var2;
    }

    private void count(StringBuilder out, int n, String one, String many)
    {
        if (n > 0)
        {
            out.append(out.length() > 0 ? ", " : "").append(n).append(' ').append(n == 1 ? one : many);
        }
    }

    private boolean isMissing(KirModule module)
    {
        return module == mod_Kirmod.MUSIC_SOUND_TEST && KirMusic.findKind(KirMusic.KIND_SOUND_TEST) == null || module == mod_Kirmod.MUSIC_0X10C && KirMusic.findKind(KirMusic.KIND_0X10C) == null;
    }

    private String stateText(KirMusic.Track track)
    {
        switch (track.getState())
        {
            case KirMusic.CONVERTING:
                return "Converting...";

            case KirMusic.WAITING:
                return "Waiting to convert";

            case KirMusic.NO_FFMPEG:
                return "Needs ffmpeg";

            case KirMusic.FAILED:
                return "Could not convert";

            default:
                return null;
        }
    }

    private void drawPanel(int x0, int y0, int x1, int y1)
    {
        drawRect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF000000);
        drawRect(x0, y0, x1, y1, 0xB0101014);
        drawRect(x0, y0, x1, y0 + 1, 0x30FFFFFF);
    }

    private void drawList(int mouseX, int mouseY)
    {
        this.startClip(this.listLeft, this.listTop, this.listRight, this.listBottom);
        Row var3 = this.getItemAt(mouseX, mouseY);
        KirMusic.Soundtrack var4 = KirMusic.getSelected();
        KirMusic.Track var5 = KirMusic.getCurrent();

        for (int var6 = 0; var6 < this.items.size(); ++var6)
        {
            Row var7 = (Row)this.items.get(var6);
            int var8 = this.listLeft + var7.x;
            int var9 = this.screenY(var7);
            int var10 = var7.h + (var7.lines != null && var7.type == Row.HEADER ? var7.lines.size() * 10 : 0);

            if (var9 + var10 < this.listTop || var9 > this.listBottom)
            {
                continue;
            }

            boolean var11 = var7 == var3;

            switch (var7.type)
            {
                case Row.HEADER:
                    this.fontRenderer.drawStringWithShadow(var7.text, var8, var9 + 2, COLOR_GOLD);
                    int var12 = var8 + this.fontRenderer.getStringWidth(var7.text) + 6;
                    drawRect(var12, var9 + 6, var8 + var7.w, var9 + 7, 0x50FFD860);

                    if (var7.lines != null)
                    {
                        for (int var13 = 0; var13 < var7.lines.size(); ++var13)
                        {
                            this.fontRenderer.drawString((String)var7.lines.get(var13), var8, var9 + 15 + var13 * 10, COLOR_GRAY);
                        }
                    }

                    break;

                case Row.SUBHEADER:
                    this.fontRenderer.drawStringWithShadow(var7.text, var8, var9 + 2, 0xC0A050);
                    break;

                case Row.TILE:
                    this.drawTile(var7, var8, var9, var7.target == var4, var11);
                    break;

                case Row.OPTION:
                    this.drawOption((KirModule)var7.target, var8, var9, var7.w, var11);
                    break;

                case Row.TRACK:
                    this.drawTrack((KirMusic.Track)var7.target, var8, var9, var7.w, var11, var7.text != null, var7.target == var5);
                    break;

                case Row.TEXT:
                    this.fontRenderer.drawString(var7.text, var8, var9, COLOR_GRAY);
            }
        }

        this.endClip();

        if (this.hasScrollbar())
        {
            int var14 = this.listRight - SCROLLBAR_W;
            int var15 = this.getThumbHeight();
            int var16 = this.listTop + (int)(this.scroll / (float)(this.contentHeight - (this.listBottom - this.listTop)) * (float)(this.listBottom - this.listTop - var15));
            drawRect(var14, this.listTop, this.listRight, this.listBottom, 0x60000000);
            drawRect(var14 + 1, var16 + 1, this.listRight - 1, var16 + var15 - 1, this.draggingScroll ? 0xFFE0E0E0 : 0xFF909090);
        }
    }

    private void drawTile(Row item, int x, int y, boolean selected, boolean hovered)
    {
        if (selected)
        {
            drawRect(x, y, x + TILE_W, y + TILE_H, 0xFFFFC000);
            drawRect(x + 1, y + 1, x + TILE_W - 1, y + TILE_H - 1, 0xFF3A2C08);
        }
        else if (hovered)
        {
            drawRect(x, y, x + TILE_W, y + TILE_H, 0xFFB0B0B0);
            drawRect(x + 1, y + 1, x + TILE_W - 1, y + TILE_H - 1, 0xFF2A2A30);
        }
        else
        {
            drawRect(x, y, x + TILE_W, y + TILE_H, 0x60000000);
        }

        KirMusic.Soundtrack var6 = (KirMusic.Soundtrack)item.target;
        int var7 = x + (TILE_W - ICON) / 2;
        int var8 = y + 5;
        this.drawCover(var6, var7, var8, ICON);
        String var9 = String.valueOf(var6.tracks.size());
        int var10 = this.fontRenderer.getStringWidth(var9);
        int var11 = var7 + ICON + 2;
        drawRect(var11 - var10 - 4, var8 + ICON - 10, var11 + 1, var8 + ICON + 1, 0xFF000000);
        drawRect(var11 - var10 - 3, var8 + ICON - 9, var11, var8 + ICON, 0xFF285028);
        this.fontRenderer.drawStringWithShadow(var9, var11 - var10 - 1, var8 + ICON - 8, 0x80FF80);
        int var12 = selected ? 0xFFE070 : (hovered ? 0xFFFFFF : 0xC8C8C8);

        for (int var13 = 0; var13 < item.lines.size(); ++var13)
        {
            this.drawCenteredString(this.fontRenderer, (String)item.lines.get(var13), x + TILE_W / 2, var8 + ICON + 4 + var13 * 9, var12);
        }
    }

    /** The soundtrack's cover, or a music disc (a jukebox for All Music) from the item and block atlases. */
    private void drawCover(KirMusic.Soundtrack soundtrack, int x, int y, int size)
    {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        int var5 = soundtrack.getCoverTexture(this.mc.renderEngine);

        if (var5 >= 0)
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, var5);
            this.drawTexturedRect(x, y, size, size, 0.0D, 0.0D, 1.0D, 1.0D);
            return;
        }

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        int var6;

        if (soundtrack.kind == KirMusic.KIND_ALL)
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.mc.renderEngine.getTexture("/terrain.png"));
            var6 = Block.jukebox.getBlockTextureFromSide(1);
        }
        else
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.mc.renderEngine.getTexture("/gui/items.png"));
            Item[] var7 = {Item.record13, Item.recordCat, Item.recordBlocks, Item.recordChirp, Item.recordFar, Item.recordMall, Item.recordMellohi, Item.recordStal, Item.recordStrad, Item.recordWard, Item.record11};
            int var8 = soundtrack.kind == KirMusic.KIND_VANILLA ? 0 : (soundtrack.id.hashCode() & 0x7FFFFFFF) % var7.length;
            var6 = var7[var8].getIconFromDamage(0);
        }

        double var9 = (double)(var6 % 16) / 16.0D;
        double var11 = (double)(var6 / 16) / 16.0D;
        this.drawTexturedRect(x, y, size, size, var9, var11, var9 + 0.0625D, var11 + 0.0625D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private void drawOption(KirModule module, int x, int y, int w, boolean hovered)
    {
        boolean var6 = this.isMissing(module);
        drawRect(x, y, x + w, y + OPTION_H, hovered ? 0x60FFFFFF : 0x40000000);

        if (hovered)
        {
            drawRect(x, y + OPTION_H - 1, x + w, y + OPTION_H, 0xFF8ED8B4);
        }

        String var7 = module.getValueName();
        int var8 = module.getValue() == module.getDefaultValue() ? 0xC8C8C8 : COLOR_GOLD;

        if (var6)
        {
            var8 = 0x707070;
        }

        int var9 = this.fontRenderer.getStringWidth(var7);
        String var10 = this.fontRenderer.trimStringToWidth(module.getTitle(), w - var9 - 14);
        this.fontRenderer.drawStringWithShadow(var10, x + 4, y + (OPTION_H - 8) / 2, var6 ? 0x808080 : 0xFFFFFF);
        this.fontRenderer.drawStringWithShadow(var7, x + w - var9 - 4, y + (OPTION_H - 8) / 2, var8);
    }

    private void drawTrack(KirMusic.Track track, int x, int y, int w, boolean hovered, boolean showAlbum, boolean playing)
    {
        if (playing)
        {
            drawRect(x - 2, y, x + w + 2, y + TRACK_H, 0x50FFC000);
        }
        else if (hovered)
        {
            drawRect(x - 2, y, x + w + 2, y + TRACK_H, 0x40FFFFFF);
        }

        boolean var8 = track.isReady();
        String var9 = this.stateText(track);
        long var10 = track.getDuration();
        String var12 = var9 != null ? var9 : (var10 > 0L ? formatTime(var10) : "");
        int var13 = this.fontRenderer.getStringWidth(var12);
        int var14 = playing ? COLOR_GOLD : (var8 ? 0xE0E0E0 : 0x707070);
        String var15 = (playing ? (KirMusic.isPaused() ? "|| " : "> ") : "") + track.title;
        int var16 = w - var13 - 8;
        String var17 = this.fontRenderer.trimStringToWidth(var15, var16);
        this.fontRenderer.drawString(var17, x + 2, y + 2, var14);

        if (showAlbum)
        {
            int var18 = x + 2 + this.fontRenderer.getStringWidth(var17) + 6;
            int var19 = x + w - var13 - 6 - var18;

            if (var19 > 30)
            {
                this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(track.soundtrack.name, var19), var18, y + 2, 0x606060);
            }
        }

        this.fontRenderer.drawString(var12, x + w - var13 - 2, y + 2, var9 != null ? 0xC06050 : 0x808080);
    }

    private void drawNowPlaying(KirMusic.Track track)
    {
        int var2 = (this.previewLeft + this.previewRight) / 2;
        int var3 = this.previewRight - this.previewLeft;
        this.drawCenteredString(this.fontRenderer, track != null ? (KirMusic.isPaused() ? "Paused" : "Now Playing") : "Not Playing", var2, this.listTop + 5, COLOR_GRAY);
        KirMusic.Soundtrack var4 = track != null ? track.soundtrack : KirMusic.getSelected();
        int var5 = this.listTop + 17;
        int var6 = this.listBottom - 24 - 6;
        // The cover gets what the text under it leaves, up to 64.
        int var7 = Math.max(24, Math.min(Math.min(64, var3 - 24), var6 - var5 - 58));

        if (var4 != null)
        {
            this.drawCover(var4, var2 - var7 / 2, var5, var7);
        }

        var5 += var7 + 4;

        if (track != null)
        {
            List var8 = this.fontRenderer.listFormattedStringToWidth(track.title, var3 - 8);

            for (int var9 = 0; var9 < Math.min(2, var8.size()); ++var9)
            {
                this.drawCenteredString(this.fontRenderer, (String)var8.get(var9), var2, var5, COLOR_GOLD);
                var5 += 10;
            }

            this.drawCenteredString(this.fontRenderer, this.trim(track.soundtrack.name, var3 - 8), var2, var5, 0x909090);
            var5 += 12;
            long var10 = KirMusic.getElapsed();
            long var12 = track.getDuration();
            this.drawCenteredString(this.fontRenderer, formatTime(var10) + (var12 > 0L ? " / " + formatTime(var12) : ""), var2, var5, 0xC0C0C0);
            var5 += 11;

            if (var12 > 0L)
            {
                int var14 = this.previewLeft + 8;
                int var15 = this.previewRight - 8;
                drawRect(var14, var5, var15, var5 + 3, 0xFF303030);
                drawRect(var14, var5, var14 + (int)((long)(var15 - var14) * Math.min(var10, var12) / var12), var5 + 3, 0xFF8ED8B4);
            }

            var5 += 7;
        }
        else
        {
            if (var4 != null)
            {
                this.drawCenteredString(this.fontRenderer, this.trim(var4.name, var3 - 8), var2, var5, COLOR_GOLD);
                var5 += 12;
            }

            long var16 = KirMusic.getCountdown();
            String var18;

            if (this.mc.gameSettings.musicVolume <= 0.0F)
            {
                var18 = "Music volume is off";
            }
            else if (this.mc.theWorld == null)
            {
                var18 = "Plays in a world";
            }
            else
            {
                var18 = var16 >= 0L ? "Next song in " + formatTime(var16) : "";
            }

            List var19 = this.fontRenderer.listFormattedStringToWidth(var18, var3 - 8);

            for (int var20 = 0; var20 < var19.size(); ++var20)
            {
                this.drawCenteredString(this.fontRenderer, (String)var19.get(var20), var2, var5, 0x909090);
                var5 += 10;
            }

            var5 += 2;
        }

        int var21 = KirMusic.getConvertingCount();
        String var22 = null;

        if (var21 > 0)
        {
            var22 = "Converting " + var21 + (var21 == 1 ? " song..." : " songs...");
        }
        else if (var4 != null && var4.isC418())
        {
            var22 = "Music by C418";
        }

        if (var22 != null && var5 + 9 <= var6)
        {
            List var23 = this.fontRenderer.listFormattedStringToWidth(var22, var3 - 8);

            for (int var24 = 0; var24 < var23.size() && var5 + 9 <= var6; ++var24)
            {
                this.drawCenteredString(this.fontRenderer, (String)var23.get(var24), var2, var5, var21 > 0 ? 0xC0A050 : 0x707070);
                var5 += 10;
            }
        }
    }

    static String formatTime(long ms)
    {
        long var2 = ms / 1000L;
        long var4 = var2 / 60L;
        long var6 = var2 % 60L;
        return var4 + ":" + (var6 < 10L ? "0" : "") + var6;
    }

    private String trim(String text, int width)
    {
        String var3 = this.fontRenderer.trimStringToWidth(text, width);
        return var3.equals(text) ? text : this.fontRenderer.trimStringToWidth(text, width - this.fontRenderer.getStringWidth("..")) + "..";
    }

    private void drawTexturedRect(int x, int y, int w, int h, double u0, double v0, double u1, double v1)
    {
        Tessellator var14 = Tessellator.instance;
        var14.startDrawingQuads();
        var14.addVertexWithUV((double)x, (double)(y + h), (double)this.zLevel, u0, v1);
        var14.addVertexWithUV((double)(x + w), (double)(y + h), (double)this.zLevel, u1, v1);
        var14.addVertexWithUV((double)(x + w), (double)y, (double)this.zLevel, u1, v0);
        var14.addVertexWithUV((double)x, (double)y, (double)this.zLevel, u0, v0);
        var14.draw();
    }

    private void drawTooltip(List lines, int x, int y)
    {
        int var4 = 0;

        for (int var5 = 0; var5 < lines.size(); ++var5)
        {
            var4 = Math.max(var4, this.fontRenderer.getStringWidth((String)lines.get(var5)));
        }

        int var6 = lines.size() * 10 - 1;
        int var7 = Math.max(4, Math.min(x + 10, this.width - var4 - 6));
        int var8 = Math.max(4, Math.min(y - 14, this.height - var6 - 6));
        this.zLevel = 300.0F;
        drawRect(var7 - 3, var8 - 3, var7 + var4 + 3, var8 + var6 + 2, 0xF0100010);
        drawRect(var7 - 3, var8 - 3, var7 + var4 + 3, var8 - 2, 0xFF5000A0);
        drawRect(var7 - 3, var8 + var6 + 1, var7 + var4 + 3, var8 + var6 + 2, 0xFF28007F);

        for (int var9 = 0; var9 < lines.size(); ++var9)
        {
            this.fontRenderer.drawStringWithShadow((String)lines.get(var9), var7, var8 + var9 * 10, var9 == 0 ? 0xFFFFFF : 0xB0B0B0);
        }

        this.zLevel = 0.0F;
    }

    private void startClip(int x0, int y0, int x1, int y1)
    {
        int var5 = this.mc.displayWidth / this.width;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * var5, this.mc.displayHeight - y1 * var5, (x1 - x0) * var5, (y1 - y0) * var5);
    }

    private void endClip()
    {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
