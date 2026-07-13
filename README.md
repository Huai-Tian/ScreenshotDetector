# ScreenshotDetector

**An application designed to detect the presence of screen capture, screen recording, and screen sharing activities on the device.**

**一个用于检测用户是否存在截屏，录屏和屏幕共享行为的软件**

**This app is the opposite of [ScreenshotFaker](https://github.com/Huai-Tian/ScreenshotFaker). It detects if the app itself is being screenshotted, recorded, or shared via screen-sharing, and whether the runtime environment is secure.**

**此应用程序代表着[ScreenshotFaker](https://github.com/Huai-Tian/ScreenshotFaker)的对立面，用于检测自身是否被截屏、录屏或共享屏幕，以及是否处于风险环境。**

**Detection Method (Under Development)**
* **Detect key-press screenshots via ScreenCaptureCallback.**
* **Monitor the media library via ContentObserver.**
* **Monitor files via FileObserver.**
* **Detect screen recording via ScreenRecordingCallback.**
* **Monitor the status of the MediaProjection service.**
* **Monitor MediaRouter for auxiliary detection.**
* **Detect screen mirroring via DisplayManager.**
* **Basic device environment security detection.**
* **Detect ScreenshotFaker characteristics.**

**检测方式（开发中）**
* **通过 ScreenCaptureCallback 检测按键截屏**
* **通过 ContentObserver 监听媒体库**
* **通过 FileObserver 监听文件**
* **通过 ScreenRecordingCallback 检测是否存在录屏**
* **监听 MediaProjection 服务状态**
* **监听 MediaRouter 用于辅助**
* **通过 DisplayManager 检测是否存在投屏**
* **基础的设备环境安全检测**
* **检测是否存在 ScreenshotFaker 特征**

**Important Notice**\
This software is currently in the early stages of development. Many features are still under construction, and existing ones may have bugs or limitations. I sincerely apologize for any errors or issues you may encounter.You are also welcome to submit Issues and Pull Requests!

**重要提示**\
本软件尚在开发早期阶段，大量的功能还在实现中，已实现的功能可能存在各种问题和不足，如果你在使用过程中遇到任何错误，我深感抱歉。同时也欢迎您提交 Issue 和 Pull Request！

**Contact**\
Welcome to join us for communication and feedback: [QQ](https://qm.qq.com/q/j2NM49cd8c)\
Alternatively, you can directly submit issues and suggestions on the project's Issue page. I will actively respond and do my best to resolve them.

**联系方式**\
欢迎加入我们进行交流和反馈：[QQ](https://qm.qq.com/q/j2NM49cd8c)\
或者，你也可以直接在本项目的 Issue 页面提交问题和建议，我会积极回复并尽力解决。

**Disclaimer**\
This project is intended for **security research**, **software testing**, and **educational purposes** only.
Please do not use this project for any illegal purposes (including but not limited to cheating in exams and data falsification).
Users must assume all legal responsibilities arising from the use of this project.

**免责声明**\
本项目仅供**安全研究、软件测试和教育目的**使用。  
请勿将本项目用于任何非法用途（包括但不限于考试作弊，数据造假）。  
使用者需自行承担因使用本项目而产生的一切法律责任。

**Thank you for your attention and support!**\
**感谢你的关注和支持！**