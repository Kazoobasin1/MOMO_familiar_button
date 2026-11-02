# 墨墨熟知按钮 (MOMO_familiar_button)

一个 LSPosed 模块：把墨墨背单词页面底部的 **三个** 按钮
`认识 / 不确定 / 不认识` 变成 **四个**
`认识 / 熟知 / 不确定 / 不认识`。

![效果](docs/screenshot.png)

> 在真机（OPPO PLZ110 / Android 17 / KernelSU + LSPosed）上针对 **墨墨 5.6.00 (versionCode 900)** 实测通过。

---

## 效果

- 底部操作栏变成四等分：`认识`(绿) `熟知`(浅蓝) `不确定`(黄) `不认识`(红)
- `熟知` 的圆角描边、字色、高度、按压态与原生按钮完全一致（直接克隆原按钮的背景 drawable 再染色）
- 点击 `熟知` 走的是 App 自己的应答链路：
  `ReviewFragment.X0(ReviewResponse.WELL_FAMILIAR.value, false)`
  与 App 顶部工具栏的「熟知」图标、长按认识弹出的「标记熟知」是同一个入口
- 原有的三个按钮、滑动、拼写、确认弹窗都不受影响

## 安装

1. 手机需要 root + LSPosed
2. 安装 `MoMoRev.apk`（到 Releases 下载，或自己构建）
3. 打开 LSPosed 管理器 → 模块 → 启用「墨墨熟知按钮」
4. 在模块的**作用域**里勾选「墨墨背单词」
5. 强制停止墨墨背单词，再重新打开

## 原理

1. 背单词界面是**传统 XML + View** 布局（`fragment_review.xml` / `fragment_study.xml`），
   底部操作栏是 `ConstraintLayout`，id = `rev_operation_bar`，
   内部是三个 0dp 宽的 `TextView` 组成的水平链：
   `rev_familiar` → `rev_vague` → `rev_forget`。
2. 模块在 `Activity.onResume` 后挂 `OnGlobalLayoutListener`，用
   `resources.getIdentifier("rev_operation_bar", "id", "com.maimemo.android.momo")` 找到操作栏。
3. 新建一个 `TextView`，插到 `rev_familiar` 与 `rev_vague` 之间，
   并把原来「认识 → 不确定」的两条约束改成「认识 → 熟知 → 不确定」，形成四段链。
4. 点击时反射调用 `ReviewFragment.X0(4, false)`。

### 两个坑

- **App 经过 R8，`androidx.constraintlayout` 也被改名了**：
  `ConstraintLayout.LayoutParams` 实际是 `s62`，字段名例如 `leftToRight` 变成了 `f`。
  所以模块不能按名字取约束字段，而是**按值反查**：
  在 `rev_familiar` 的 LayoutParams 里找「值 == rev_vague 的 id」的那个 int 字段，
  它就是 `rightToLeft`；同理在 `rev_vague` 里找「值 == rev_familiar 的 id」得到 `leftToRight`。
  这样即使字段名再变也不会挂。
- **`androidx.fragment.app` 同样被混淆**（`getFragments()` / `getView()` 都改名了），
  所以模块不通过 FragmentManager 找 Fragment，而是 hook
  `ReviewFragment.onViewCreated(View, Bundle)` / `StudyFragment.onViewCreated(...)`
  捕获实例（这两个是 App 自己的类，名字没被混淆）。

## 构建

无需 Gradle：

```powershell
.\build.ps1
# 产物 build\MoMoRev.apk
```

依赖 JDK 17 + Android SDK build-tools 34（脚本里默认 `C:\AndroidDev\jdk-17`、`C:\AndroidDev\sdk`，
可以用 `-JdkHome` / `-SdkRoot` 覆盖）。

`stubs/` 里是**编译期**用的 Xposed API 桩，不会打进 dex，运行时用 LSPosed 提供的真类。

## 目录

```
app/
  AndroidManifest.xml          xposedmodule / xposedscope 元数据
  assets/xposed_init           入口类名
  src/com/carrot/momorev/
    MainHook.java              全部逻辑
stubs/de/robv/android/xposed/  编译期桩（不打包）
docs/screenshot.png
build.ps1
```

## 已知限制 / 待办

- 只在 **5.6.00** 上验证过。App 升级后 `rev_operation_bar` 等资源 id 一般不变（资源名没混淆），
  但 `X0` / `G0` 这类短方法名是混淆产物，升级后可能需要改。
- 目前固定发送「熟知」。若单词已经是熟知状态，App 自己的图标会变成「取消熟知」，
  模块暂时不做这个切换。
- 设计上绕过了 App 的会员特权校验（`PrivilegeCode.mark_word_well_familiar`），
  与 App 自带的 `G0` 路径不同。
- 未在「新词学习」模式（`StudyFragment`）下实测。
- 颜色固定浅蓝（`0xFF4FC3F7`），不随主题切换。

## 免责声明

仅供个人学习与逆向技术研究使用：

- 本仓库**不包含**墨墨背单词的任何代码、资源或安装包，只包含运行时注入的模块代码
- 不修改、不重打包原 App，不破解其付费内容
- 使用本模块可能违反该 App 的用户协议，由此产生的一切后果由使用者自行承担
- 请勿用于商业用途或二次分发
