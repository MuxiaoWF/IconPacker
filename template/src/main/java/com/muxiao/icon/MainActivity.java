package com.muxiao.icon;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.content.res.XmlResourceParser;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图标包应用的主界面：列出包内每个图标与它对应的应用包名，按
 * 「已安装 / 未安装 / 未映射」三分类给出筛选与行内状态徽标。
 *
 * <h3>为什么需要这个类</h3>
 * 模板清单声明了 Activity（既是"图标包是否出现在应用抽屉"的入口，也是启动器
 * 识别图标包的 THEMES intent 目标），没有实现类就会 ClassNotFoundException；
 * 而在此之前它只弹一句 Toast 就 finish，用户在手机上点开图标包看不到任何内容。
 * 这里把它做成一个只读的"包内容一览"：图标 + 包名 + 状态。正因为**图标是用户自己
 * 配的**，打开就能确认哪些应用换了图标、哪些映射的目标还没装、哪些图还没映射——
 * 后两类正是筛选条存在的原因：几百个条目里找"还没生效的"不能靠肉眼滚全表。
 *
 * <h3>数据来源（全部来自产物自身，零网络、零额外清单）</h3>
 * 打包引擎在构建时把两份清单写进产物的 res/xml：
 * <ul>
 *   <li>{@code appfilter.xml}：`组件 → 图标名`，即启动器实际读取的映射表（唯一事实来源）；</li>
 *   <li>{@code drawable.xml}：包内全部图标名（含未映射的）。</li>
 * </ul>
 * 两者都是 aapt2 编译过的二进制 XML，用 {@link android.content.res.Resources#getXml(int)}
 * 即可按框架自己的解析器读回来——与启动器读的是同一份文件、同一种解析方式，
 * 因此这里显示的内容与启动器实际生效的内容天然一致，不存在第二份清单可以漂移。
 *
 * <h3>实现约束</h3>
 * 模板不含任何第三方依赖与 Kotlin 源码（那些东西会以 kotlin-stdlib 的形式给每个产物
 * 多加 2MB 以上），所以界面只用框架控件在代码里拼装：无 layout 资源、无 AndroidX。
 * 深浅配色也只发生在代码里——清单主题保持原样，不触碰 AxmlPatcher 对清单的
 * 定点替换约束；"已安装"的判定受清单 &lt;queries&gt; 的可见范围限制（见清单注释），
 * 对不在 MAIN 入口里的包会一并显示为未安装。
 */
public class MainActivity extends Activity {

    private static final String TAG = "IconPackMain";

    /** 一个 `<item>` 的两种属性名，见 core/packer 的 AppFilterFactory */
    private static final String ATTR_COMPONENT = "component";
    private static final String ATTR_DRAWABLE = "drawable";

    /** 行状态：按"有没有映射 / 目标装没装"三分类，与筛选条档位一一对应 */
    private static final int STATUS_INSTALLED = 0;
    private static final int STATUS_NOT_INSTALLED = 1;
    private static final int STATUS_UNMAPPED = 2;
    private static final int STATUS_COUNT = 3;

    /** 筛选档位：0=全部；1..3 与 STATUS_* 对齐；-1 表示不过滤 */
    private static final int FILTER_ALL = 0;
    private static final int FILTER_INSTALLED = 1;
    private static final int FILTER_NOT_INSTALLED = 2;
    private static final int FILTER_UNMAPPED = 3;
    private static final int[] FILTER_TO_STATUS = {
            -1, STATUS_INSTALLED, STATUS_NOT_INSTALLED, STATUS_UNMAPPED};

    /**
     * 「如何启用」指引的关闭状态存 SharedPreferences：关一次就不再出现。
     * 产物重装会重置回显示——指引本来就该在全新安装时露出，属预期行为。
     */
    private static final String PREFS_UI = "ic_pack_ui";
    private static final String KEY_HINT_DISMISSED = "enable_hint_dismissed";

    /** 包内条目：一个图标 + 它被指派给的组件（未映射时 component 为 null） */
    private static final class Entry {
        String drawableName;
        String component;
        String packageName;
        /** 装载后由 [resolveStatuses] 依据 PackageManager 定出，此后只读 */
        int status;
    }

    /** 目标应用的安装状态与显示名；每个包名只查一次 PackageManager */
    private static final class AppInfo {
        final boolean installed;
        final String label;

        AppInfo(boolean installed, String label) {
            this.installed = installed;
            this.label = label;
        }
    }

    /** 复用的行视图引用，避免每次 getView 都重新 findViewById（本页无 layout 资源，故手工持有） */
    private static final class Row {
        ImageView icon;
        TextView primary;
        TextView secondary;
        TextView chip;
    }

    /** 界面配色：浅/深两套在代码里选（清单主题不动，理由见类注释） */
    private static final class Palette {
        int surface;
        int textPrimary;
        int textSecondary;
        int chipIdleBg;
        int chipIdleText;
        final int[] statusBg = new int[STATUS_COUNT];
        final int[] statusText = new int[STATUS_COUNT];
    }

    private final List<Entry> entries = new ArrayList<>();
    /** 当前筛选下的条目，适配器只看它；FILTER_ALL 时与 entries 同序同长 */
    private final List<Entry> visible = new ArrayList<>();
    /** 图标名 → 资源 id；0 表示解析失败（缓存下来，避免每行都重查一遍） */
    private final Map<String, Integer> iconIds = new HashMap<>();
    /** 包名 → 安装状态/显示名；未安装的包同样缓存，避免重复 binder 查询 */
    private final Map<String, AppInfo> appInfos = new HashMap<>();
    /** 各状态条目数（筛选条上的计数） */
    private final int[] statusCounts = new int[STATUS_COUNT];

    /** arsc 里的资源包名（可能与 manifest 的包名不同，见下方 resolveResourcePackage） */
    private String resourcePackage = null;
    private Palette palette;
    /** 三个状态的徽标文案（与筛选条后三档同词），装载时取好 */
    private String[] statusLabels;
    private TextView[] filterChips;
    private TextView emptyTitle;
    private TextView emptyHint;
    private EntryAdapter adapter;
    private int currentFilter = FILTER_ALL;
    private View hintStrip;
    private boolean hintDismissed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        palette = paletteFor(dark);
        statusLabels = new String[]{
                getString(R.string.ic_pack_filter_installed),
                getString(R.string.ic_pack_not_installed),
                getString(R.string.ic_pack_unmapped)};
        hintDismissed = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .getBoolean(KEY_HINT_DISMISSED, false);
        // 状态栏融入界面底色：浅色底配深色图标，深色底用默认白图标
        getWindow().setStatusBarColor(palette.surface);
        if (!dark) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        resourcePackage = resolveResourcePackage();
        loadEntries();
        resolveStatuses();
        setContentView(buildContentView());
    }

    // ---- 数据装载 ----

    /** 按 drawable.xml 的顺序装载全部图标，再按 appfilter.xml 补上映射到的包名 */
    private void loadEntries() {
        Map<String, String> drawableToComponent = new LinkedHashMap<>();
        for (Item item : readItems(R.xml.appfilter)) {
            if (item.component != null && item.drawable != null) {
                drawableToComponent.put(item.drawable, item.component);
            }
        }

        for (Item item : readItems(R.xml.drawable)) {
            if (item.drawable == null) continue;
            Entry entry = new Entry();
            entry.drawableName = item.drawable;
            entry.component = drawableToComponent.get(item.drawable);
            entry.packageName = componentPackage(entry.component);
            entries.add(entry);
        }

        // 兜底：drawable.xml 为空却存在映射（旧产物 / 异常包）时，至少把映射关系显示出来
        if (entries.isEmpty()) {
            for (Map.Entry<String, String> it : drawableToComponent.entrySet()) {
                Entry entry = new Entry();
                entry.drawableName = it.getKey();
                entry.component = it.getValue();
                entry.packageName = componentPackage(entry.component);
                entries.add(entry);
            }
        }
    }

    /** 给每个条目定状态并累计计数；状态只依赖"映射目标是否已安装"，与图标资源无关 */
    private void resolveStatuses() {
        for (Entry entry : entries) {
            if (entry.packageName == null) {
                entry.status = STATUS_UNMAPPED;
            } else {
                entry.status = appInfoFor(entry.packageName).installed
                        ? STATUS_INSTALLED : STATUS_NOT_INSTALLED;
            }
            statusCounts[entry.status]++;
        }
    }

    private AppInfo appInfoFor(String packageName) {
        AppInfo cached = appInfos.get(packageName);
        if (cached != null) return cached;
        boolean installed;
        String label = null;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence loaded = info.loadLabel(getPackageManager());
            installed = true;
            // TextUtils 而不是 isEmpty()：Android 的 CharSequence 没有 isEmpty()（那是 JDK 15 才加的）
            if (!android.text.TextUtils.isEmpty(loaded)) label = loaded.toString();
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        AppInfo result = new AppInfo(installed, label);
        appInfos.put(packageName, result);
        return result;
    }

    /** `<item>` 的原始属性 */
    private static final class Item {
        String component;
        String drawable;
    }

    /**
     * 读出一份编译后 XML 里的全部 `<item>`。
     * 属性优先按**名称**取；只有名称不可用（框架对自产 AXML 的兼容分支）时才按出现顺序兜底，
     * 且兜底依赖两份文件的固定形态：appfilter 恒为 `[component, drawable]`、drawable.xml 恒为
     * `[drawable]`（见 AppFilterFactory）。解析失败不抛给用户，退化成空列表 → 界面显示空态。
     */
    private List<Item> readItems(int xmlResId) {
        List<Item> out = new ArrayList<>();
        try (XmlResourceParser parser = getResources().getXml(xmlResId)) {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    out.add(readItem(parser));
                }
                event = parser.next();
            }
        } catch (Exception e) {
            // 读不出来只影响本页显示：启动器走的 native 解析路径与此无关，不应崩溃
            Log.w(TAG, "无法解析 res/xml 资源 #" + Integer.toHexString(xmlResId), e);
        }
        return out;
    }

    private static Item readItem(XmlResourceParser parser) {
        Item item = new Item();
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            String name = parser.getAttributeName(i);
            String value = parser.getAttributeValue(i);
            if (ATTR_COMPONENT.equals(name)) {
                item.component = value;
            } else if (ATTR_DRAWABLE.equals(name)) {
                item.drawable = value;
            }
        }
        if (item.component == null && item.drawable == null && count > 0) {
            if (count == 1) {
                item.drawable = parser.getAttributeValue(0);
            } else {
                item.component = parser.getAttributeValue(0);
                item.drawable = parser.getAttributeValue(1);
            }
        }
        return item;
    }

    /**
     * 从 component 里取出包名。
     * 与 core/packer 的 `IconPackManifest.packageOfComponent` 同一口径：appfilter 的 component
     * 写法很杂（`ComponentInfo{包名/活动}`、省略前缀、只给包名都会出现），一律只认斜杠前的包名。
     * 模板不依赖 core/packer，故此处独立实现一份（几十字节，不值得为它引依赖）。
     */
    static String componentPackage(String component) {
        if (component == null) return null;
        String value = component.trim();
        int brace = value.indexOf('{');
        if (brace >= 0) {
            int end = value.indexOf('}', brace);
            value = end > brace ? value.substring(brace + 1, end) : value.substring(brace + 1);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析 arsc 里的资源包名。
     * 不能直接用 {@link #getPackageName()}：打包引擎只改写清单的 `package` 属性
     * （多包共存的前提，见 AxmlPatcher.setPackageName），resources.arsc 里仍是模板原值。
     * 两者的字符串不一致，而按名查资源（`getIdentifier`）依赖的是 arsc 里那个。
     */
    private String resolveResourcePackage() {
        try {
            return getResources().getResourcePackageName(R.xml.appfilter);
        } catch (Exception e) {
            Log.w(TAG, "无法解析资源包名，回退到清单包名", e);
            return getPackageName();
        }
    }

    // ---- 界面 ----

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.surface);

        root.addView(buildHeader());

        if (entries.isEmpty()) {
            root.addView(buildEmptyState(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return root;
        }

        root.addView(buildFilterBar());
        root.addView(buildEntryList(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * 轻量头部：包图标 + 名称/包名，底色跟随深浅界面。
     * 旧版是一整块实心品牌蓝横幅，约占 1/5 屏高；其中统计行与筛选条上的计数
     * 重复、启用指引是读过一次就多余的内容，都降级——统计删除、指引收成
     * 可关闭的一行（关闭状态持久化）。品牌蓝只留在选中筛选胶囊上做点缀。
     */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int h = dp(16);
        header.setPadding(h, dp(14), h, dp(10));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        Drawable packIcon = loadPackIcon();
        if (packIcon != null) {
            ImageView badge = new ImageView(this);
            badge.setImageDrawable(packIcon);
            // 启动器图标原始多为方形位图，圆角裁剪与筛选胶囊保持同一视觉语言
            badge.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            badge.setClipToOutline(true);
            int side = dp(46);
            identity.addView(badge, new LinearLayout.LayoutParams(side, side));
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(loadPackLabel(), 18, palette.textPrimary, Typeface.BOLD));
        TextView packId = text(getPackageName(), 12, palette.textSecondary, Typeface.NORMAL);
        packId.setTypeface(Typeface.MONOSPACE);
        texts.addView(packId);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.leftMargin = dp(14);
        identity.addView(texts, textsParams);

        header.addView(identity);
        if (!hintDismissed) {
            hintStrip = buildHintStrip();
            header.addView(hintStrip, topMargin(dp(8)));
        }
        return header;
    }

    /** 「如何启用」一行：小字提示 + 关闭钮；关闭后写 SharedPreferences，本页不再出现 */
    private View buildHintStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.addView(text("ⓘ " + getString(R.string.ic_pack_how_to_enable),
                12, palette.textSecondary, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 14, palette.textSecondary, Typeface.NORMAL);
        close.setPadding(dp(12), dp(12), dp(4), dp(12));
        close.setContentDescription(getString(R.string.ic_pack_dismiss));
        close.setOnClickListener(v -> dismissHint());
        strip.addView(close);
        return strip;
    }

    private void dismissHint() {
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                .putBoolean(KEY_HINT_DISMISSED, true).apply();
        if (hintStrip != null) hintStrip.setVisibility(View.GONE);
    }

    /** 产物自身的启动器图标（打包时已由引擎替换为用户设置的包图标）；取不到就不占位 */
    private Drawable loadPackIcon() {
        try {
            return getPackageManager().getApplicationIcon(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * 筛选条：全部 / 已安装 / 未安装 / 未映射 四档胶囊，各带计数。
     * 放在列表上方、头部之外——头部会随滚动离开屏幕，筛选要随时可点。
     */
    private View buildFilterBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int h = dp(16);
        bar.setPadding(h, dp(12), h, dp(4));
        scroller.addView(bar);

        String[] labels = {getString(R.string.ic_pack_filter_all),
                statusLabels[0], statusLabels[1], statusLabels[2]};
        int[] counts = {entries.size(), statusCounts[0], statusCounts[1], statusCounts[2]};
        filterChips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(this);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setText(labels[i] + " " + counts[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            bar.addView(chip, params);
            final int filter = i;
            chip.setOnClickListener(v -> applyFilter(filter));
            filterChips[i] = chip;
        }
        applyFilter(FILTER_ALL);
        return scroller;
    }

    private void applyFilter(int filter) {
        currentFilter = filter;
        visible.clear();
        int want = FILTER_TO_STATUS[filter];
        for (Entry entry : entries) {
            if (want < 0 || entry.status == want) visible.add(entry);
        }
        if (filterChips != null) {
            for (int i = 0; i < filterChips.length; i++) {
                styleFilterChip(filterChips[i], i == filter);
            }
        }
        updateEmptyCopy();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void styleFilterChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(selected ? BRAND : palette.chipIdleBg);
        chip.setBackground(bg);
        chip.setTextColor(selected ? Color.WHITE : palette.chipIdleText);
        chip.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** 列表与空态叠在同一层 FrameLayout：筛选把列表筛空时由 ListView 自动切到空态 */
    private View buildEntryList() {
        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> copyPackageName(visible.get(position)));
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View empty = buildEmptyState();
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        return body;
    }

    private View buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(48), dp(32), dp(48));

        emptyTitle = text("", 16, palette.textPrimary, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        box.addView(emptyTitle);
        emptyHint = text("", 13, palette.textSecondary, Typeface.NORMAL);
        emptyHint.setGravity(Gravity.CENTER);
        box.addView(emptyHint, topMargin(dp(8)));
        updateEmptyCopy();
        return box;
    }

    /**
     * 空态文案随筛选变化：分类筛空多半是好消息（"目标应用都已安装"），
     * 与"包本身是空的"（回到 IconPacker 重新打包）要区分开。
     */
    private void updateEmptyCopy() {
        if (emptyTitle == null) return;
        int titleRes;
        int hintRes = 0;
        switch (currentFilter) {
            case FILTER_INSTALLED:
                titleRes = R.string.ic_pack_empty_installed;
                break;
            case FILTER_NOT_INSTALLED:
                titleRes = R.string.ic_pack_empty_not_installed;
                break;
            case FILTER_UNMAPPED:
                titleRes = R.string.ic_pack_empty_unmapped;
                break;
            default:
                titleRes = R.string.ic_pack_empty_title;
                hintRes = R.string.ic_pack_empty_hint;
        }
        emptyTitle.setText(titleRes);
        if (hintRes == 0) {
            emptyHint.setVisibility(View.GONE);
        } else {
            emptyHint.setText(hintRes);
            emptyHint.setVisibility(View.VISIBLE);
        }
    }

    /** 图标包自身的应用名（打包时由引擎写进清单 label，这里读回来） */
    private String loadPackLabel() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), 0);
            CharSequence label = info.loadLabel(getPackageManager());
            // TextUtils 而不是 isEmpty()：Android 的 CharSequence 没有 isEmpty()（那是 JDK 15 才加的）
            if (!android.text.TextUtils.isEmpty(label)) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // 查询自己失败基本不可能，真失败了也不该因为标题而崩溃
        }
        return getPackageName();
    }

    private final class EntryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                LinearLayout item = new LinearLayout(MainActivity.this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                int h = dp(16);
                item.setPadding(h, dp(10), h, dp(10));

                ImageView icon = new ImageView(MainActivity.this);
                item.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textsParams =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textsParams.leftMargin = dp(14);
                textsParams.rightMargin = dp(8);

                TextView primary = text("", 15, palette.textPrimary, Typeface.NORMAL);
                TextView secondary = text("", 12, palette.textSecondary, Typeface.NORMAL);
                texts.addView(primary);
                texts.addView(secondary);
                item.addView(texts, textsParams);

                TextView chip = new TextView(MainActivity.this);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                chip.setPadding(dp(8), dp(3), dp(8), dp(3));

                row = new Row();
                row.icon = icon;
                row.primary = primary;
                row.secondary = secondary;
                row.chip = chip;
                item.addView(chip, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                item.setTag(row);
                convertView = item;
            } else {
                row = (Row) convertView.getTag();
            }

            Entry entry = visible.get(position);
            row.icon.setImageDrawable(loadIcon(entry.drawableName));
            switch (entry.status) {
                case STATUS_INSTALLED:
                    // 装了的应用用人话名当主行，包名降为次要行（复制动作仍取包名）
                    AppInfo info = appInfoFor(entry.packageName);
                    row.primary.setText(info.label != null ? info.label : entry.packageName);
                    row.primary.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                    row.secondary.setText(entry.packageName);
                    row.secondary.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    break;
                case STATUS_NOT_INSTALLED:
                    row.primary.setText(entry.packageName);
                    row.primary.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    row.secondary.setText(statusLabels[STATUS_NOT_INSTALLED]);
                    row.secondary.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                    break;
                default:
                    row.primary.setText(entry.drawableName);
                    row.primary.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    row.secondary.setText(statusLabels[STATUS_UNMAPPED]);
                    row.secondary.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            }
            styleStatusChip(row.chip, entry.status);
            return convertView;
        }
    }

    /** 行尾状态徽标：与筛选条同词同色，扫一眼颜色即可对上"这个分类还有多少行" */
    private void styleStatusChip(TextView chip, int status) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(palette.statusBg[status]);
        chip.setBackground(bg);
        chip.setTextColor(palette.statusText[status]);
        chip.setText(statusLabels[status]);
    }

    /**
     * 按图标名取出产物里的 drawable。
     * 三级查找：arsc 资源包名 → 清单包名 → 放弃（行里只有文字）。前两级覆盖"包名被改写 /
     * 未被改写"两种产物；都取不到说明资源名对不上（模板与引擎的槽位命名漂移），
     * 此时静默降级比抛异常好——界面其余内容仍然可用。
     * 必须用 {@link android.content.res.Resources#getIdentifier} 按名查：图标名
     * （ic_0001…ic_1024）是运行时从 drawable.xml 解析出来的动态数据，编译期无法用
     * R.drawable.xxx 静态引用，故这里无法避免资源反射。该 lint 警告（DiscouragedApi）
     * 对"动态资源名"场景属预期，显式抑制。
     */
    @SuppressLint("DiscouragedApi")
    private Drawable loadIcon(String name) {
        Integer cached = iconIds.get(name);
        int id = cached == null ? 0 : cached;
        if (cached == null) {
            id = getResources().getIdentifier(name, "drawable", resourcePackage);
            if (id == 0) id = getResources().getIdentifier(name, "drawable", getPackageName());
            if (id == 0) Log.w(TAG, "找不到图标资源：" + name);
            iconIds.put(name, id);
        }
        return id == 0 ? null : getResources().getDrawable(id, getTheme());
    }

    /** 点一行复制包名——本页最实用的动作就是把包名拿走（反馈、上报、手动指派都要用） */
    private void copyPackageName(Entry entry) {
        String text = entry.packageName != null ? entry.packageName : entry.drawableName;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("package", text));
        Toast.makeText(this, getString(R.string.ic_pack_copied, text), Toast.LENGTH_SHORT).show();
    }

    // ---- 小工具 ----

    private static final int BRAND = Color.parseColor("#1565C0");

    private static Palette paletteFor(boolean dark) {
        Palette p = new Palette();
        if (dark) {
            p.surface = 0xFF121212;
            p.textPrimary = 0xFFE3E3E3;
            p.textSecondary = 0xFF9E9E9E;
            p.chipIdleBg = 0xFF2B2B2F;
            p.chipIdleText = 0xFFBFBFBF;
            p.statusBg[STATUS_INSTALLED] = 0xFF1F3324;
            p.statusText[STATUS_INSTALLED] = 0xFF9BD8A5;
            p.statusBg[STATUS_NOT_INSTALLED] = 0xFF3A2C1A;
            p.statusText[STATUS_NOT_INSTALLED] = 0xFFF0BE8A;
            p.statusBg[STATUS_UNMAPPED] = 0xFF2A323A;
            p.statusText[STATUS_UNMAPPED] = 0xFFA9B8C4;
        } else {
            p.surface = Color.WHITE;
            p.textPrimary = 0xFF212121;
            p.textSecondary = 0xFF757575;
            p.chipIdleBg = 0xFFEFF1F4;
            p.chipIdleText = 0xFF5F6368;
            p.statusBg[STATUS_INSTALLED] = 0xFFE1F3E6;
            p.statusText[STATUS_INSTALLED] = 0xFF1D6F2F;
            p.statusBg[STATUS_NOT_INSTALLED] = 0xFFFDEEDD;
            p.statusText[STATUS_NOT_INSTALLED] = 0xFF9A4E00;
            p.statusBg[STATUS_UNMAPPED] = 0xFFECEFF1;
            p.statusText[STATUS_UNMAPPED] = 0xFF546E7A;
        }
        return p;
    }

    /** @param style {@link Typeface#NORMAL} / {@link Typeface#BOLD} 之类的字形常量 */
    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private LinearLayout.LayoutParams topMargin(int px) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = px;
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
