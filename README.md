# Voxy Iris/Oculus Compatibility Fixes

## 问题描述

Voxy 模组与 Iris/Oculus 光影模组之间存在两个兼容性问题：

### 问题 1: 无限递归调用

Voxy 与 Iris 光影模组之间存在递归调用循环：

```
createRenderer() → voxypipelinepatch() → reload0() → Iris.reload()
  ↓
触发 LevelRenderer 重建 → createRenderer() → 循环
```

这会导致游戏启动后进程卡住，Render线程消耗大量CPU时间但无法完成加载。

### 问题 2: StampedLock 死锁

切换光影设置时，Render线程等待 ChunkBuilder.shutdownThreads() 完成（通过 Thread.join()），而 Worker线程在 ActiveSectionTracker.acquire() 的 StampedLock 上阻塞等待，形成死锁：

```
Render线程: ChunkBuilder.shutdownThreads() → Thread.join() (等待Worker)
Worker线程: ActiveSectionTracker.acquire() → StampedLock.readLock() (阻塞)
```

## 修复方案

### Fix 1: IrisUtil 递归保护

在 `IrisUtil.class` 中添加递归保护：

1. 新增静态字段 `private static boolean isReloading = false`
2. 在 `voxypipelinepatch()` 方法开头检查，如果正在reload则直接返回
3. 在 `reload0()` 方法中设置/清除 `isReloading` 标志

### Fix 2: ActiveSectionTracker 死锁保护

在 `ActiveSectionTracker.class` 中添加关闭检测：

1. 在 `acquire(long, boolean)` 方法开头检查 `engine.isLive`
2. 在 `acquire(int, int, int, int, boolean)` 方法开头检查 `engine.isLive`
3. 如果 engine 不活跃（isLive == false），立即返回 null 而不是阻塞等待锁

## 文件说明

- `mods/voxy-0.2.13-alpha-patched-v2.jar` - **推荐版本**（仅修复递归，稳定可用）
- `mods/voxy-0.2.13-alpha-patched-v3.jar` - v3版本（有问题，不推荐使用）
- `source/voxy-0.2.13-alpha-original.jar` - 原始模组文件（备份）
- `patch/CombinedVoxyPatch.java` - 综合字节码修改脚本
- `patch/BytecodePatch.java` - 仅修复递归的脚本（v2，推荐）
- `patch/ActiveSectionTrackerPatch.java` - 仅修复死锁的脚本（有问题）

## 使用方法

**推荐使用 v2 版本：**

1. 将 `mods/voxy-0.2.13-alpha-patched-v2.jar` 复制到 Minecraft 模组目录
2. 删除或备份原版 Voxy 模组
3. 重新启动游戏

**注意：v3 版本的 ActiveSectionTracker 补丁会导致游戏启动时卡在主菜单，请使用 v2 版本。**

## 技术细节

使用 JDK 内置 ASM 库修改字节码，**必须使用 COMPUTE_FRAMES** 自动计算 StackMapTable：

```bash
# 编译
javac --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED CombinedVoxyPatch.java

# 运行补丁
java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp patch \
    CombinedVoxyPatch source/voxy-0.2.13-alpha-original.jar \
    mods/voxy-0.2.13-alpha-patched-v3.jar
```

### 关键修复点

- **StackMapTable问题**: 使用 `ClassWriter.COMPUTE_FRAMES` 而不是 `COMPUTE_MAXS`
- **类型缺失问题**: 自定义 SafeClassWriter 处理 `getCommonSuperClass` 异常

## 版本信息

- Voxy 版本: 0.2.13-alpha (1.20.1)
- Oculus 版本: 1.8.0
- Minecraft: 1.20.1
- Forge: 47.4.18

## 修改日期

- v1: 2026-04-28 (有StackMapTable错误)
- v2: 2026-04-28 (修复VerifyError，仅修复递归)
- v3: 2026-04-28 (修复递归+StampedLock死锁)