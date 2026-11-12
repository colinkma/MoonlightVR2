# MoonlightVR2

这是基于 [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) 修改的 Cardboard 头戴显示器版本，保留 Moonlight 原有串流和 MediaCodec 硬件解码路径。

## VR 显示模式

设置中提供两种可选模式：

### 固定 SBS 模式（原模式）

- 将电脑画面固定为左右眼并排（SBS）显示。
- 左右眼显示同一份完整的二维画面。
- 保留 Google Cardboard 镜片光学校正。
- 不启用头部追踪，不生成虚拟 3D 巨幕或电影院场景。

### 固定空间屏幕模式（新增）

- 使用 Cardboard 头部姿态追踪，将串流画面放置在三维空间中。
- 解锁时，屏幕随头部方向移动；锁定后，屏幕保持在按下锁定时的空间位置。
- 画面上方提供拉近/推远、锁定/解锁位置和旋转 90° 控件，方便侧躺时校正画面方向。
- 继续使用左右眼独立视图与 Cardboard 镜片光学校正。

## 摄像头透明模式

在设置中启用“低头时启用摄像头透明模式”后：

- 仅在固定空间屏幕模式下生效。
- 低头达到触发角度时自动启动后置摄像头，将真实环境显示为背景，串流窗口仍显示在前景。
- 抬头后自动停止摄像头。
- 需要授予摄像头权限；关闭此设置时不会启动摄像头。

摄像头只在应用处于前台且 VR 串流活动时使用。Android 系统的摄像头隐私指示仍然有效。

## 项目基础

Moonlight 是用于连接 NVIDIA GameStream 兼容主机及 [Sunshine](https://github.com/LizardByte/Sunshine) 的开源串流客户端。

## 说明

本项目面向 Google Cardboard 兼容头戴设备。不同手机的屏幕比例、陀螺仪和镜片参数可能不同，实际显示效果需要在对应设备上测试。镜片光学校正强度可在应用设置中调整。

## 上游项目

- [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)
- [Moonlight 官网](https://moonlight-stream.org)
- [Sunshine](https://github.com/LizardByte/Sunshine)
