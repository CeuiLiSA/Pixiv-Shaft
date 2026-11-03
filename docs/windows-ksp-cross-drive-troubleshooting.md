# Windows 跨盘符构建排查指南 (KSP AA Worker `different roots`)

> 适用场景：在 Windows 环境下克隆或开发 Pixiv-Shaft，且**项目所在盘符与系统盘（C 盘）不一致**（例如项目位于 `D:\`、`E:\`、`R:\` 等），构建时遇到 KSP 崩溃。

---

## 1. 问题现象

在 Android Studio 中触发构建（Build / Run / Test），或在终端执行 `./gradlew assembleDebug` 等编译任务时，Gradle 构建在 Kotlin 符号处理阶段失败，抛出类似如下异常：

```text
Execution failed for task ':app:kspGithubDebugKotlin'.
> A failure occurred while executing com.google.devtools.ksp.gradle.KspAAWorkerAction
   > this and base files have different roots: C:\Users\<Username>\.gradle\caches\9.1.0\transforms\...\jetified-okhttp3-integration-4.16.0-api.jar!\com\bumptech\glide\annotation\compiler\GlideIndexer_...class and R:\Pixiv-Shaft-1\app.
```

或者显式抛出：
```text
java.lang.IllegalArgumentException: this and base files have different roots: C:\... and R:\...
```

---

## 2. 根因剖析 (Root Cause)

1. **KSP 2.x Analysis API 路径相对化缺陷**：
   - 项目启用了 KSP（Kotlin Symbol Processing）。在处理注解（如 Glide compiler / Room 等注解处理器生成的代码或 transformed jar）时，KSP 2 的 `KspAAWorkerAction`（基于 Kotlin Analysis API）会在内部调用 `File.relativeTo(base)` 或虚拟文件系统路径归一化逻辑，尝试计算依赖文件相对于工程目录的相对路径。
2. **Windows 多驱动器根（Roots）限制**：
   - 在 Windows 文件系统中，每个驱动器盘符（如 `C:`、`D:`、`R:`）都是独立的文件系统根（Root）。
   - Java NIO 与 Kotlin 标准库明确规定：**当两个路径属于不同的根（Root）时，无法计算相对路径**。此时调用 `pathA.relativeTo(pathB)` 会直接抛出 `IllegalArgumentException("this and base files have different roots")`。
3. **上游官方 Issue**：
   - 该问题是 Google KSP 官方仓库已知且长期挂起的 Bug：[google/ksp#1079](https://github.com/google/ksp/issues/1079)（及关联 issues [#1538](https://github.com/google/ksp/issues/1538)、[#1637](https://github.com/google/ksp/issues/1637)）。在 KSP 官方彻底重构 Windows 跨盘符虚拟路径映射之前，只要工程与 Gradle 缓存跨盘，该问题必然触发。

---

## 3. 核心解决原则

> **工程源码目录（Project Root）必须与 Gradle 用户主目录（Gradle User Home / caches）位于同一个 Windows 盘符（同一 Root）。**

---

## 4. 推荐解决方案

### 方案 A：在 Android Studio 中重写 Gradle 用户主目录（最推荐，IDE 开发首选）

无需修改任何项目代码或系统全局环境变量，仅在 IDE 中调整 Gradle 配置：

1. 打开 Android Studio 设置：
   - 菜单栏：`File` $\rightarrow$ `Settings`（快捷键：`Ctrl + Alt + S`）。
2. 定位到 Gradle 配置：
   - 展开左侧：`Build, Execution, Deployment` $\rightarrow$ `Build Tools` $\rightarrow$ `Gradle`。
3. 修改 **Gradle user home**：
   - 默认值通常为系统盘路径（如 `C:\Users\<你的用户名>\.gradle`）。
   - 将其修改为**与工程所在盘符一致的真实物理路径**：
     - 若工程位于 `R:\Pixiv-Shaft-1`，则填写 `R:\.gradle`（或 `R:\gradle-user-home`）。
     - 若工程位于 `D:\Projects\Pixiv-Shaft`，则填写 `D:\.gradle`。
4. 点击 `Apply` 并 `OK` 保存，随后执行一次 **Sync Project with Gradle Files** 并重新构建。

> 💡 **效果**：Android Studio 会将 Wrapper、下载的依赖 Jar 包、KSP transforms 缓存全量存放在项目同盘目录下，两端盘符完全相同，彻底消除跨盘异常。

---

### 方案 B：配置 `GRADLE_USER_HOME` 环境变量（命令行 / 脚本构建）

如果经常使用终端执行 `./gradlew assembleDebug`、单元测试或运行构建脚本：

- **单次终端临时生效**：
  - PowerShell：
    ```powershell
    $env:GRADLE_USER_HOME = "R:\.gradle"
    .\gradlew assembleGithubDebug
    ```
  - CMD：
    ```cmd
    set GRADLE_USER_HOME=R:\.gradle
    gradlew.bat assembleGithubDebug
    ```
- **系统永久生效**：
  - 打开 Windows `系统属性` $\rightarrow$ `高级系统设置` $\rightarrow$ `环境变量`。
  - 在 `用户变量` 或 `系统变量` 中新建：
    - 变量名：`GRADLE_USER_HOME`
    - 变量值：`R:\.gradle`（替换为你工程所在盘符的真实路径）。
  - 重启终端或 Android Studio 使其读取最新环境变量。

---

### 方案 C：将项目克隆到系统盘（C 盘）（轻度贡献者备选）

若不想调整 Gradle 全局缓存路径且 C 盘可用空间充足，可直接将仓库克隆至 C 盘目录下开发：
```bash
cd C:\Users\<Username>\Projects
git clone https://github.com/CeuiLiSA/Pixiv-Shaft.git
```
由于工程源码与默认的 `C:\Users\<Username>\.gradle` 均位于 `C:` 盘根下，天然规避此问题。

---

## 5. 深度避坑：为什么「符号链接（mklink）」通常无效？

在排查该问题时，开发者往往会尝试通过创建符号链接来省去重新下载依赖的耗时：
> 例如：在 `R:` 盘创建符号链接指向 `C:` 盘：
> `mklink /D R:\.gradle C:\Users\<Username>\.gradle`
> 并在 Android Studio 中将 Gradle user home 设置为 `R:\.gradle`。

### 为什么这样做依然会报错？
- **真实路径解析（Canonicalization）机制**：
  JVM 底层的 `File.getCanonicalPath()`、Java NIO 的 `Path.toRealPath()` 以及 IntelliJ/Gradle 虚拟文件系统 (VFS) 在进行依赖索引和任务执行前，会**主动解引用**符号链接（Symbolic Link）与目录联接（Directory Junction），还原出其真实物理目标路径（Canonical Path）。
- **结果**：
  即使用户界面显示为 `R:\.gradle`，在 KSP AA Worker 执行时解析到的 transformed jar 物理路径依然是 `C:\Users\<Username>\.gradle\...`。当其与 `R:\Pixiv-Shaft-1\app` 进行路径相对化计算时，底层依旧是两个不同盘符，仍然必定抛出 `this and base files have different roots`。

> ⚠️ **结论**：请勿试图通过「跨盘软链接」欺骗 KSP。必须将 Gradle User Home 指向与项目**同盘的真实物理文件夹**。

---

## 6. 后续维护建议与磁盘空间说明

1. **初次构建耗时**：
   - 切换 Gradle User Home 路径后，首次构建会重新下载 Gradle Wrapper、Android 构建插件及第三方依赖库，耗时由网络决定，属于正常现象。
2. **清理旧缓存**：
   - 迁移后，原 `C:\Users\<Username>\.gradle\caches` 中的旧缓存不会自动删除。在确认新环境构建正常后，可手动清理旧盘的 `.gradle/caches` 释放宝贵的系统盘空间。
