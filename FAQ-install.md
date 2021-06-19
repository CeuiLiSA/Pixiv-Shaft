## 无法安装 - 通常解决方法

1. 请先删除旧版本的应用，避免冲突。例如之前在google play下载的，请删除之后再安装GitHub版
2. 尝试重新安装
3. 删除后安装仍然失败，请再次检查是否已经解除安装了其他版本
4. 确认以上之后，在自行承担风险下，请进行第二部分

## 无法安装 - 第二部分 (风险自负)

1. 请确认通常解决方法无效之后才进行以下步骤
2. 以下步骤会使用adb。**`adb`有风险请自行承担**
3. 开启手机的开发者模式，详细请自行百度/谷歌
4. 在开发者模式中寻找 "USB侦错"
5. **"USB侦错" 有风险请自行承担**
6. 找一台电脑，下载[相关工具](https://developer.android.com/studio/releases/platform-tools#downloads "SDK Platform Tools")
7. (以下默认为windows电脑)
8. 解压工具，并找到`adb.exe`的位置
9. 在该位置打开`powershell`/`cmd`,可以在文件浏览器左上角`文件`那里找到
10. **"USB侦错" 有风险请自行承担**
11. 用USB数据线，连接电脑和手机，可能需要选择MTP模式
12. 在手机的弹窗上允许USB侦错，取消勾选永远允许
13. 在`powershell`中输入：`.\adb devices` 确认连接成功。在`list of devices attached`下应该看到一行
14. 输入以下指令以完全卸载Shaft：`.\adb uninstall "ceui.lisa.pixiv"`
15. 在手机浏览[这个repo的latest release](https://github.com/CeuiLiSA/Pixiv-Shaft/releases/latest "Latest Release")或者你想安装的版本
16. 点开`Assets`部分，点击`app-release.apk`下载
17. 下载完成后安装
18. 确认安装成功后，在电脑端
