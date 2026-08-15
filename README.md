# CODA Android MVP

CODA（现场工作助手）Android MVP 的实现工程。产品定稿保持 v1.0；UI、技术和视觉规格为 v1.1 冻结稿。

## 目录

- `app/src/main/java/com/coda/workbench/`：Android 与 Compose 源码
- `app/src/main/res/`：Android 资源
- `app/src/test/`：本地单元测试
- `outputs/`：四份正式规格文档
- `work/`：审查过程记录，不作为产品契约

## 打开与构建

使用 Android Studio 打开本目录，等待 Gradle 同步后运行 `app` 配置。工程要求 JDK 17、compileSdk 35、minSdk 26。

当前入口只提供今日工作台的视觉壳层；业务状态机、UseCase 和本地存储按技术稿中的 M1-M8 验收门逐步实现。

## M1 测试

在 Windows 终端中运行：

```bat
.\scripts\test-m1.bat
```

测试报告生成在 `app/build/reports/tests/testDebugUnitTest/index.html`。
