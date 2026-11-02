package com.carrot.momorev;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 墨墨背单词：底部「认识 / 不确定 / 不认识」三个按钮 -> 四个「认识 / 熟知 / 不确定 / 不认识」。
 *
 * 背单词界面是传统 XML 布局，底部操作栏为 ConstraintLayout(id=rev_operation_bar)，
 * 内含 rev_familiar / rev_vague / rev_forget 三个 0dp 宽 TextView 组成的水平链。
 * 在链中插入一个同样 0dp 宽的 TextView，把 three-chain 变成 four-chain。
 *
 * 注意：App 经过 R8，ConstraintLayout.LayoutParams 被重命名为 s62 且字段名也被混淆，
 * 所以这里不按名字取约束字段，而是「按值反查」——某个 int 字段的值等于邻居 view 的 id，
 * 那它就是那条约束。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG = "com.maimemo.android.momo";
    private static final String TAG = "MoMoRev: ";
    private static final boolean DEBUG = true;

    /** 熟知按钮配色：浅蓝，与 认识(绿) / 不确定(黄) / 不认识(红) 区分 */
    private static final int WELL_COLOR = 0xFF4FC3F7;
    private static final int WELL_FAMILIAR_FALLBACK = 4;

    private static final Map<View, Boolean> DONE_BARS = new WeakHashMap<View, Boolean>();
    private static final Map<View, Boolean> WATCHED_ROOTS = new WeakHashMap<View, Boolean>();

    /** 当前背单词 Fragment 及其根 view（通过 hook onViewCreated 捕获） */
    private static final Object FRAG_LOCK = new Object();
    private static final String[] FRAGMENT_CLASSES = {
            "com.maimemo.android.momo.revision.ReviewFragment",
            "com.maimemo.android.momo.study.StudyFragment"
    };
    private static volatile Object sFragment;
    private static volatile View sFragmentRoot;
    private static volatile boolean sFragmentHooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName) || !PKG.equals(lpparam.processName)) {
            return;
        }
        log("module loaded in " + lpparam.processName);
        try {
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!(param.thisObject instanceof Application)) {
                        return;
                    }
                    ((Application) param.thisObject).registerActivityLifecycleCallbacks(new Lifecycle());
                    log("activity lifecycle registered");
                    final ClassLoader cl = ((Application) param.thisObject).getClassLoader();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            for (int i = 0; i < 40 && !sFragmentHooked; i++) {
                                tryInstallFragmentHooks(cl);
                                if (sFragmentHooked) {
                                    return;
                                }
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                    }, "momorev-hook").start();
                }
            });
        } catch (Throwable t) {
            log("hook Application.attach failed: " + t);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            watch(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    private static void watch(final Activity activity) {
        final View decor;
        try {
            decor = activity.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (decor == null) {
            return;
        }
        synchronized (WATCHED_ROOTS) {
            if (WATCHED_ROOTS.containsKey(decor)) {
                return;
            }
            WATCHED_ROOTS.put(decor, Boolean.TRUE);
        }
        decor.post(new Runnable() {
            @Override
            public void run() {
                inject(activity);
            }
        });
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                inject(activity);
            }
        });
    }

    // ---------------------------------------------------------------- injection

    private static void inject(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            tryInstallFragmentHooks(activity.getClassLoader());
            View root = activity.getWindow().getDecorView();
            Resources res = activity.getResources();
            int barId = res.getIdentifier("rev_operation_bar", "id", PKG);
            if (barId == 0) {
                return;
            }
            View bar = root.findViewById(barId);
            if (!(bar instanceof ViewGroup)) {
                return;
            }
            synchronized (DONE_BARS) {
                if (DONE_BARS.containsKey(bar)) {
                    return;
                }
            }
            if (buildButton(activity, (ViewGroup) bar, res)) {
                synchronized (DONE_BARS) {
                    DONE_BARS.put(bar, Boolean.TRUE);
                }
            }
        } catch (Throwable t) {
            log("inject failed: " + t);
        }
    }

    private static boolean buildButton(Activity activity, ViewGroup bar, Resources res) throws Throwable {
        int famId = res.getIdentifier("rev_familiar", "id", PKG);
        int vagueId = res.getIdentifier("rev_vague", "id", PKG);
        if (famId == 0 || vagueId == 0) {
            log("cannot resolve button ids");
            return false;
        }
        View fam = bar.findViewById(famId);
        View vague = bar.findViewById(vagueId);
        if (!(fam instanceof TextView) || vague == null) {
            log("cannot find existing buttons");
            return false;
        }
        TextView src = (TextView) fam;
        ViewGroup.LayoutParams famLp = fam.getLayoutParams();
        ViewGroup.LayoutParams vagueLp = vague.getLayoutParams();
        if (famLp == null || vagueLp == null) {
            log("no layout params");
            return false;
        }
        Class<?> clpClass = famLp.getClass();

        // 按值反查约束字段：fam.right -> vague.left, vague.left -> fam.right
        Field rightField = findIntField(clpClass, famLp, vagueId);
        Field leftField = findIntField(clpClass, vagueLp, famId);
        if (rightField == null || leftField == null) {
            log("constraint fields not found, right=" + rightField + " left=" + leftField);
            return false;
        }

        TextView tv = new TextView(activity);
        String label = "熟知";
        int labelId = res.getIdentifier("rev_well_familiar", "string", PKG);
        if (labelId != 0) {
            String s = res.getString(labelId);
            if (s != null && s.length() > 0) {
                label = s;
            }
        }
        tv.setText(label);
        tv.setTag("momorev_well_familiar");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
        tv.setTypeface(src.getTypeface());
        tv.setGravity(src.getGravity());
        tv.setIncludeFontPadding(true);
        tv.setPadding(src.getPaddingLeft(), src.getPaddingTop(), src.getPaddingRight(), src.getPaddingBottom());
        tv.setTextColor(WELL_COLOR);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setClickable(true);
        tv.setFocusable(true);

        int newId = View.generateViewId();
        tv.setId(newId);

        // 以「不确定」的 LayoutParams 为模板：左接认识，右接不认识 —— 正好是我们想要的
        Object ourLp = copyLp(clpClass, vagueLp, 0, vagueLp.height);
        rightField.setInt(ourLp, vagueId);          // our.right -> vague.left
        leftField.setInt(ourLp, famId);             // our.left  -> fam.right

        // 把原来「认识 -> 不确定」改成「认识 -> 熟知 -> 不确定」
        rightField.setInt(famLp, newId);
        fam.setLayoutParams(famLp);
        leftField.setInt(vagueLp, newId);
        vague.setLayoutParams(vagueLp);

        style(tv, src, res);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onWellFamiliar(v);
            }
        });
        bar.addView(tv, (ViewGroup.LayoutParams) ourLp);
        bar.requestLayout();
        log("4th button injected (" + label + ") fields=" + leftField.getName() + "/" + rightField.getName()
                + " lp=" + clpClass.getName());
        return true;
    }

    private static final String[] SKIP_FIELDS = {
            "width", "height", "leftMargin", "rightMargin", "topMargin", "bottomMargin",
            "marginStart", "marginEnd", "gravity", "horizontalWeight"
    };

    /** 在 lp 的 public int 字段里找值等于 value 的那个（即某条指向 value 视图的约束） */
    private static Field findIntField(Class<?> cls, Object lp, int value) {
        Field found = null;
        String foundName = null;
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) {
                continue;
            }
            String name = f.getName();
            boolean skip = false;
            for (String s : SKIP_FIELDS) {
                if (s.equals(name)) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            try {
                if (f.getInt(lp) != value) {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (found == null) {
                found = f;
                foundName = name;
            } else {
                log("ambiguous field for " + value + ": " + foundName + " / " + name + " (keep first)");
            }
        }
        return found;
    }

    private static Object copyLp(Class<?> cls, Object src, int width, int height) throws Throwable {
        Constructor<?> ctor = cls.getConstructor(int.class, int.class);
        Object dst = ctor.newInstance(Integer.valueOf(width), Integer.valueOf(height));
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) {
                continue;
            }
            try {
                f.setInt(dst, f.getInt(src));
            } catch (Throwable ignored) {
            }
        }
        ViewGroup.LayoutParams d = (ViewGroup.LayoutParams) dst;
        d.width = width;
        d.height = height;
        return dst;
    }

    /** 克隆「认识」按钮的背景（圆角描边），染色成蓝色；再自建一个按压态前景 */
    private static void style(TextView tv, TextView src, Resources res) {
        float density = res.getDisplayMetrics().density;
        int radius = (int) (10f * density + 0.5f);
        int stroke = Math.max(1, (int) (2f * density + 0.5f));

        try {
            Drawable bg = src.getBackground();
            if (bg != null && bg.getConstantState() != null) {
                Drawable copy = bg.getConstantState().newDrawable(res).mutate();
                copy.setColorFilter(new PorterDuffColorFilter(WELL_COLOR, PorterDuff.Mode.SRC_IN));
                tv.setBackground(copy);
                log("background cloned");
            } else {
                tv.setBackground(roundStroke(radius, stroke, WELL_COLOR, 0x00000000));
                log("background fallback (src bg null)");
            }
        } catch (Throwable t) {
            log("background clone failed: " + t);
            tv.setBackground(roundStroke(radius, stroke, WELL_COLOR, 0x00000000));
        }

        try {
            android.graphics.drawable.StateListDrawable fg = new android.graphics.drawable.StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_pressed},
                    roundStroke(radius, stroke, WELL_COLOR, (WELL_COLOR & 0x00FFFFFF) | 0x1A000000));
            fg.addState(new int[0], new android.graphics.drawable.ColorDrawable(0x00000000));
            tv.setForeground(fg);
        } catch (Throwable t) {
            log("foreground failed: " + t);
        }
    }

    private static GradientDrawable roundStroke(int radius, int stroke, int color, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setStroke(stroke, color);
        g.setColor(fill);
        return g;
    }

    // ---------------------------------------------------------------- click

    private static void onWellFamiliar(View v) {
        try {
            Activity activity = activityOf(v);
            if (activity == null) {
                toast(v, "未找到 Activity");
                return;
            }
            java.util.ArrayList<Object> candidates = new java.util.ArrayList<Object>();
            Object found = findFragment(v, activity);
            if (found != null) {
                candidates.add(found);
            }
            Object captured = sFragment;
            if (captured != null && !candidates.contains(captured)) {
                candidates.add(captured);
            }
            if (candidates.isEmpty()) {
                toast(v, "未找到背单词页面");
                log("no fragment candidate");
                return;
            }

            Throwable last = null;
            for (Object fragment : candidates) {
                int value = wellFamiliarValue(fragment);
                Method x0 = findMethod(fragment.getClass(), "X0", int.class, boolean.class);
                if (x0 == null) {
                    continue;
                }
                try {
                    x0.setAccessible(true);
                    x0.invoke(fragment, Integer.valueOf(value), Boolean.FALSE);
                    log("X0(" + value + ", false) on " + fragment.getClass().getName());
                    return;
                } catch (Throwable t) {
                    last = t;
                    log("X0 failed on " + fragment.getClass().getName() + ": " + t);
                }
            }
            for (Object fragment : candidates) {
                Method g0 = findStaticWithInstance(fragment.getClass(), "G0", fragment);
                if (g0 == null) {
                    continue;
                }
                try {
                    g0.setAccessible(true);
                    g0.invoke(null, fragment, Boolean.TRUE, Boolean.TRUE);
                    log("G0 on " + fragment.getClass().getName());
                    return;
                } catch (Throwable t) {
                    last = t;
                    log("G0 failed: " + t);
                }
            }
            toast(v, "熟知调用失败: " + (last == null ? "无匹配方法" : last.toString()));
            log("all candidates failed, last=" + last);
        } catch (Throwable t) {
            log("onWellFamiliar error: " + t);
            toast(v, "熟知异常: " + t);
        }
    }

    private static int wellFamiliarValue(Object fragment) {
        try {
            Class<?> rr = Class.forName("com.maimemo.android.momo.revision.ReviewResponse",
                    false, fragment.getClass().getClassLoader());
            Object[] constants = rr.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if ("WELL_FAMILIAR".equals(((Enum<?>) c).name())) {
                        return rr.getField("value").getInt(c);
                    }
                }
            }
        } catch (Throwable t) {
            log("read ReviewResponse failed, use fallback: " + t);
        }
        return WELL_FAMILIAR_FALLBACK;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 找 G0(ReviewFragment, boolean, boolean) 这类第一个参数是实例本身的静态方法 */
    private static Method findStaticWithInstance(Class<?> cls, String name, Object instance) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[0].isInstance(instance)
                        && ps[1] == boolean.class && ps[2] == boolean.class) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 通过 hook 捕获；捕获不到时用 duck typing 兜底扫描（androidx 被整体混淆） */
    private static Object findFragment(View v, Activity activity) {
        Object frag = sFragment;
        View root = sFragmentRoot;
        if (frag != null && (root == null || isAncestor(root, v))) {
            return frag;
        }
        return scanFragment(activity, v);
    }

    private static boolean isAncestor(View ancestor, View child) {
        View cur = child;
        while (cur != null) {
            if (cur == ancestor) {
                return true;
            }
            Object p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static Object scanFragment(Activity activity, View v) {
        try {
            Object fm = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
            List<?> fragments = anyList(fm);
            if (fragments == null) {
                log("no fragment list method found");
                return null;
            }
            View cur = v;
            while (cur != null) {
                for (Object f : fragments) {
                    if (anyView(f) == cur) {
                        return f;
                    }
                }
                Object p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        } catch (Throwable t) {
            log("scanFragment failed: " + t);
        }
        return null;
    }

    /** 找到「无参、返回 List、元素有 View getter」的方法，即 getFragments() 的混淆版本 */
    private static List<?> anyList(Object fm) {
        for (Method m : fm.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || !List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                Object r = m.invoke(fm);
                if (r instanceof List) {
                    List<?> list = (List<?>) r;
                    if (!list.isEmpty()) {
                        Object first = list.get(0);
                        if (first != null && anyView(first) != null) {
                            log("fragment list via " + m.getName());
                            return list;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 找到「无参、返回 View」的方法，即 getView() 的混淆版本 */
    private static View anyView(Object f) {
        for (Method m : f.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || !View.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                Object r = m.invoke(f);
                if (r instanceof View) {
                    return (View) r;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void tryInstallFragmentHooks(ClassLoader cl) {
        if (sFragmentHooked) {
            return;
        }
        synchronized (FRAG_LOCK) {
            if (sFragmentHooked) {
                return;
            }
            boolean any = false;
            for (String name : FRAGMENT_CLASSES) {
                try {
                    final Class<?> c = Class.forName(name, false, cl);
                    XposedBridge.hookAllMethods(c, "onViewCreated", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args != null && p.args.length > 0 && p.args[0] instanceof View) {
                                sFragment = p.thisObject;
                                sFragmentRoot = (View) p.args[0];
                                log("fragment captured: " + c.getName());
                            }
                        }
                    });
                    any = true;
                    log("fragment hook installed: " + name);
                } catch (Throwable t) {
                    log("fragment hook failed: " + name + " -> " + t);
                }
            }
            sFragmentHooked = any;
        }
    }

    private static Activity activityOf(View v) {
        Context c = v.getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static void toast(View v, String msg) {
        if (!DEBUG) {
            return;
        }
        try {
            Toast.makeText(v.getContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        if (DEBUG) {
            XposedBridge.log(TAG + msg);
        }
    }
}
