# Eta-Motorola-Custom

这是为Motorola XT2611-1手机定制的ETA版本，专门适配天禧AI（com.lenovo.xbb）。

## 项目特点

1. **独立包名**：使用 `com.custom.eta.motorola` 包名，不影响原版ETA
2. **天禧AI支持**：添加了对联想天禧AI（想帮帮）的Hook支持
3. **开关控制**：在设置界面添加了针对天禧AI Hook的开关
4. **自动化编译**：配置了GitHub Actions，自动编译APK

## 技术原理

基于原版ETA项目，通过Xposed Hook系统的`VoiceInteractionService`，拦截AI请求并转发到自定义的`AgentRuntimeService`，从而实现用自定义模型（ETA后端）替换系统AI。

## 适配目标

- **设备型号**：Motorola XT2611-1
- **Android版本**：Android 16 (SDK 36)
- **目标应用**：联想天禧AI（想帮帮）`com.lenovo.xbb`

## 编译与使用

1. 克隆本仓库
2. 使用Android Studio打开项目
3. 连接手机并启用USB调试
4. 运行项目到手机

## 许可证

本项目基于原始ETA项目进行修改，遵循相同的MIT许可证。

## 致谢

- [Mangi-11/Eta](https://github.com/Mangi-11/Eta) - 原始ETA项目
- Xposed框架 - 提供Hook能力