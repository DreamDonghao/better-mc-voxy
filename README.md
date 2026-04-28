# Voxy Iris/Oculus Recursive Call Fix

## 问题描述

Voxy 模组与 Iris/Oculus 光影模组之间存在兼容性问题，导致无限递归调用：

```
createRenderer() → voxypipelinepatch() → reload0() → Iris.reload()
  ↓
触发 LevelRenderer 重建 → createRenderer() → 循环
```

这会导致游戏启动后进程卡住，Render线程消耗大量CPU时间但无法完成加载。

## 修复方案

在 `IrisUtil.class` 中添加递归保护：

1. 新增静态字段 `private static boolean isReloading = false`
2. 在 `voxypipelinepatch()` 方法开头检查，如果正在reload则直接返回
3. 在 `reload0()` 方法中设置/清除 `isReloading` 标志

## 文件说明

- `mods/voxy-0.2.13-alpha-patched.jar` - 修复后的模组文件
- `source/voxy-0.2.13-alpha-original.jar` - 原始模组文件（备份）
- `patch/BytecodePatch.java` - 字节码修改脚本

## 使用方法

1. 将 `mods/voxy-0.2.13-alpha-patched.jar` 复制到 Minecraft 模组目录
2. 删除或备份原版 Voxy 模组
3. 重新启动游戏

## 技术细节

使用 JDK 内置 ASM 库修改字节码：

```bash
javac --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED BytecodePatch.java
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED BytecodePatch \
    IrisUtil.class IrisUtil.class
```

## 版本信息

- Voxy 版本: 0.2.13-alpha (1.20.1)
- Oculus 版本: 1.8.0
- Minecraft: 1.20.1
- Forge: 47.4.18

## 修改日期

2026-04-28
