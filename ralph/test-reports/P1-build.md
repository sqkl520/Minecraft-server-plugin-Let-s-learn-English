# 编译测试报告 P1-build

### 判定：PASS

- 命令：`mvn clean package -DskipTests`（JAVA_HOME=D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64）
- 结果：**BUILD SUCCESS**（71 source files + 3 test source files，Total time 11.626 s）
- JAR 产物：`target/Let's Learn English!-1.0.7-SNAPSHOT.jar`（364,762 bytes / 约 356 KB）
- 覆盖范围：本轮并行缺陷修复约 15 个文件（中文服止血 + 仓库卫生）

### 新增编译警告

无。构建输出中 `[WARNING]` 计数为 0，`[ERROR]` 计数为 0。

仅存在以下**既有**提示，按判定标准不计为新增警告：

| 类型 | 位置 | 说明 |
|------|------|------|
| deprecation | `util/VersionAdapter.java` | 使用/覆盖已过时 API（既有，按标准排除） |
| annotation-processing | 编译期 info note | 类路径发现注解处理器但未显式指定 `-processor`（既有，非警告级） |

### 错误清单

无编译错误。

| 文件:行号 | 错误信息 |
|-----------|----------|
| — | 无 |
