package com.muxiao.icon;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.annotation.SuppressLint;
import android.content.res.XmlResourceParser;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
 * 图标包应用的主界面：列出包内每个图标与它对应的应用包名。
 *
 * <h3>为什么需要这个类</h3>
 * 模板清单声明了 Activity（既是"图标包是否出现在应用抽屉"的入口，也是启动器
 * 识别图标包的 THEMES intent 目标），没有实现类就会 ClassNotFoundException；
 * 而在此之前它只弹一句 Toast 就 finish，用户在手机上点开图标包看不到任何内容。
 * 这里把它做成一个只读的"包内容一览"：图标 + 包名，正因为**图标是用户自己配的**，
 * 打开就能确认哪些应用换了图标、哪些还没换。
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
 */
public class MainActivity extends Activity {

    private static final String TAG = "IconPackMain";

    /** 一个 `<item>` 的两种属性名，见 core/packer 的 AppFilterFactory */
    private static final String ATTR_COMPONENT = "component";
    private static final String ATTR_DRAWABLE = "drawable";

    /** 包内条目：一个图标 + 它被指派给的组件（未映射时 component 为 null） */
    private static final class Entry {
        String drawableName;
        String component;
        String packageName;
    }

    /** 复用的行视图引用，避免每次 getView 都重新 findViewById（本页无 layout 资源，故手工持有） */
    private static final class Row {
        ImageView icon;
        TextView primary;
        TextView secondary;
    }

    private final List<Entry> entries = new ArrayList<>();
    /** 图标名 → 资源 id；0 表示解析失败（缓存下来，避免每行都重查一遍） */
    private final Map<String, Integer> iconIds = new HashMap<>();
    /** 包名 → 应用名；未安装的包记为空串，同样缓存 */
    private final Map<String, String> appLabels = new HashMap<>();
    /** arsc 里的资源包名（可能与 manifest 的包名不同，见下方 resolveResourcePackage） */
    private String resourcePackage = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resourcePackage = resolveResourcePackage();
        loadEntries();
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
        root.setBackgroundColor(Color.WHITE);

        root.addView(buildHeader());

        if (entries.isEmpty()) {
            root.addView(buildEmptyState());
        } else {
            ListView list = new ListView(this);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setAdapter(new EntryAdapter());
            list.setOnItemClickListener((parent, view, position, id) -> copyPackageName(entries.get(position)));
            root.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int h = dp(20);
        header.setPadding(h, dp(24), h, dp(16));
        header.setBackgroundColor(BRAND);

        int mapped = 0;
        for (Entry entry : entries) {
            if (entry.packageName != null) mapped++;
        }

        header.addView(text(loadPackLabel(), 20, Color.WHITE, Typeface.BOLD));
        TextView packId = text(
                getString(R.string.ic_pack_label_prefix) + " " + getPackageName(),
                12, HALF_WHITE, Typeface.NORMAL);
        packId.setTypeface(Typeface.MONOSPACE);
        header.addView(packId);
        header.addView(text(getString(R.string.ic_pack_stats, entries.size(), mapped),
                12, HALF_WHITE, Typeface.NORMAL));
        header.addView(text(getString(R.string.ic_pack_how_to_enable),
                11, HALF_WHITE, Typeface.NORMAL), topMargin(dp(8)));
        return header;
    }

    private View buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(48), dp(32), dp(48));

        box.addView(text(getString(R.string.ic_pack_empty_title), 16, TEXT_PRIMARY, Typeface.BOLD));
        box.addView(text(getString(R.string.ic_pack_empty_hint), 13, TEXT_SECONDARY, Typeface.NORMAL),
                topMargin(dp(8)));
        return box;
    }

    /** 图标包自身的应用名（打包时由引擎写进清单 label，这里读回来） */
    private String loadPackLabel() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), 0);
            CharSequence label = info.loadLabel(getPackageManager());
            if (label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // 查询自己失败基本不可能，真失败了也不该因为标题而崩溃
        }
        return getPackageName();
    }

    private final class EntryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
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

                TextView primary = text("", 15, TEXT_PRIMARY, Typeface.NORMAL);
                primary.setTypeface(Typeface.MONOSPACE);
                TextView secondary = text("", 12, TEXT_SECONDARY, Typeface.NORMAL);
                texts.addView(primary);
                texts.addView(secondary);
                item.addView(texts, textsParams);

                row = new Row();
                row.icon = icon;
                row.primary = primary;
                row.secondary = secondary;
                item.setTag(row);
                convertView = item;
            } else {
                row = (Row) convertView.getTag();
            }

            Entry entry = entries.get(position);
            row.icon.setImageDrawable(loadIcon(entry.drawableName));
            if (entry.packageName != null) {
                row.primary.setText(entry.packageName);
                row.secondary.setText(loadAppLabel(entry.packageName));
            } else {
                row.primary.setText(getString(R.string.ic_pack_unmapped));
                // 未映射时把图标名放在次要行：用户至少能认出这是自己导入的哪张图
                row.secondary.setText(entry.drawableName);
            }
            return convertView;
        }
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

    /** 目标应用名；未安装时明确标注，而不是留一片空白 */
    private String loadAppLabel(String packageName) {
        String cached = appLabels.get(packageName);
        if (cached != null) return cached;
        String label;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            label = String.valueOf(info.loadLabel(getPackageManager()));
        } catch (PackageManager.NameNotFoundException e) {
            label = getString(R.string.ic_pack_not_installed);
        }
        appLabels.put(packageName, label);
        return label;
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
    private static final int HALF_WHITE = 0xCCFFFFFF;
    private static final int TEXT_PRIMARY = Color.parseColor("#212121");
    private static final int TEXT_SECONDARY = Color.parseColor("#757575");

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
