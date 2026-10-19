# FriendsWinePlugin 源码

本仓库仅保存 `ports/folia-26.3`（FriendsWinePlugin 1.1.0，Paper / Folia）的源码及相关开发文件，不包含其他 Fabric、Forge 或 NeoForge 工程。

## 提交范围

- `ports/folia-26.3/src/`：插件源码、BetterModel 兼容后端、模型导出器、测试代码、YAML 配置及文本测试样例。
- `ports/folia-26.3/tools/`：模型、资源处理与回归测试脚本及 JSON 数据。
- `ports/folia-26.3/` 下的 Gradle 配置、Wrapper 和插件说明。
- `mod/src/main/java/com/friendswine/JellyAnimation.java`：插件编译所需的共享动画类，仅保留此文件，不包含原模组其他代码。

Gradle Wrapper 的 `gradle-wrapper.jar` 是构建入口依赖，不是本插件的编译产物，因此保留。

## 未提交的文件与构建前提

按仅推送代码的要求，本仓库不包含构建输出、缓存、日志、IDE 配置、第三方插件 JAR、资源归档和原始二进制素材。现有构建脚本及测试保留原有路径；**仅克隆本仓库不能直接完成插件打包或全部资源测试**。

完整打包前，需要自行在本地准备：

- `ports/folia-26.3/content/builtin-1.1.0.zip`：当前版本必需的内置资源归档。
- `ports/folia-26.3/content/builtin-26.3.zip`：部分资源开发工具使用的旧版输入归档。
- `ports/folia-26.3/src/test/resources/regressions/mesh-models-0.1.3.zip`：部分模型回归测试使用的归档。

重新生成玩偶及音乐资源时，还需要原工程的以下素材（同样未提交）：

- `assets/source/doll.bbmodel`
- `mod/src/main/resources/assets/friendswine/textures/item/remote.png`
- `mod/src/main/resources/assets/friendswine/sounds/doll_music.ogg`

依赖、JDK 25、构建步骤及兼容性边界见 [插件说明](ports/folia-26.3/README.md)。素材与前置插件须遵守各自许可；它们不随本次源码提交分发。
