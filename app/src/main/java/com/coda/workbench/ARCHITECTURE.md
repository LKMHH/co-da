# 源码分层

- `core/model`：业务实体、枚举和不可变值对象
- `core/rules`：状态机、业务日期、排班、文本规范化和同义词等纯 Kotlin 规则
- `core/usecase`：用例实现（原 `domain/usecase`，按技术稿 §2.3-2 等价修订并入 core）
- `data/local`：Room Entity、Dao、Database 与迁移
- `data/backup`：备份 DTO、ZIP 编解码、校验与导入事务
- `data/repository`：数据访问实现
- `platform/`：通知调度、闹钟网关、备份文件存储等平台适配
- `di/`：Hilt 组合根（AppModule）
- `ui/*`：按页面组织的 Compose 屏幕与 ViewModel（home/fault/work/handover/search/settings/attendance/schedule）
- `ui/theme`：Material 3 颜色、字体和主题

页面层只能通过 `core/usecase` 访问业务数据；不要在 Composable 中直接修改状态或数据库。错误处理采用异常式（技术稿 §2.3-1 批准的等价实现），接口签名以代码为准。

> 遗留清理项：`domain/`、`feature/` 为空目录壳，无源码，待删除。
