# MoonlightVR 固定画面头戴显示器版

这是基于原版 Moonlight for Android 修改的版本。相较原版及原 VR 分支，本版本已调整为**纯粹固定画面的头戴显示器版本**：

- 将电脑串流画面固定为左右眼并排（SBS）显示。
- 左右眼显示同一份完整的二维画面，不生成虚拟 3D 巨幕或电影院场景。
- 保留 Google Cardboard 镜片光学校正，以适配头戴设备镜片。
- 保留 Moonlight 原有串流与硬件视频解码流程。

本版本不包含头部追踪、随头部移动的虚拟屏幕、曲面屏幕、天空盒或摄像头画中画等非必要 VR 场景功能。

## 项目基础

本项目基于 [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)。Moonlight 是用于连接 NVIDIA GameStream 兼容主机及 [Sunshine](https://github.com/LizardByte/Sunshine) 的开源串流客户端。

## 说明

本版本主要面向搭配 Google Cardboard 兼容头戴设备使用。不同设备的屏幕比例和镜片参数可能不同，实际显示效果需要在对应设备上测试；镜片校正强度可在应用设置中调整。

## 原版项目

- [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)
- [Moonlight 官网](https://moonlight-stream.org)
- [Sunshine](https://github.com/LizardByte/Sunshine)
