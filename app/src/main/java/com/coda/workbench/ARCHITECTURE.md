# 源码分层

- `core/model`：业务实体、枚举和不可变值对象
- `core/rules`：状态机、业务日期、出勤复用等纯 Kotlin 规则
- `data/local`：本地数据库与备份恢复实现
- `data/repository`：数据访问实现
- `domain/usecase`：技术稿已定义的 UseCase 边界
- `feature/*`：按页面与工作流组织的 Compose UI 和 ViewModel
- `ui/theme`：Material 3 颜色、字体和主题

页面层只能通过 `domain/usecase` 或查询接口访问业务数据；不要在 Composable 中直接修改状态或数据库。
