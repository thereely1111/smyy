# smyy · 数模演易前端

数模演易的前端工作台，基于 **Vue 3 + TypeScript + Vite + Element Plus + Pinia** 构建，使用 hash 路由，开发端口为 `5181`。

本 README 只说明本项目的前端启动方式。后端属于独立项目，请直接阅读 [`agent-platform-java/README.md`](agent-platform-java/README.md)。

## 项目结构

```text
smyy/
├── src/                    # 前端源码
├── public/                 # 静态资源
├── scripts/                # 构建与运行检查脚本
├── tests/                  # 前端测试
├── agent-platform-java/    # 后端项目，请看其中的 README
├── dist/                   # 前端构建产物
├── .env.example            # 环境变量模板
├── package.json            # 前端脚本与依赖
└── vite.config.ts          # Vite 配置与后端代理
```

## 前置环境

- Node.js `20+`
- pnpm
- 可访问并已启动的后端服务，默认地址为 `http://localhost:8080`

如果还没有安装 pnpm：

```bash
npm install --global pnpm
```

## 启动项目

### 1. 启动后端

后端启动、数据库、Redis、环境变量和模型配置均在独立项目中维护：

[`agent-platform-java/README.md`](agent-platform-java/README.md)

请先按照后端 README 启动服务，并确认后端监听 `8080` 端口。

### 2. 启动前端

在本项目根目录执行：

```bash
pnpm install
```

复制环境变量模板：

```bash
# Windows PowerShell
Copy-Item .env.example .env.local

# macOS / Linux
cp .env.example .env.local
```

如后端不是默认地址，修改 `.env.local`：

```dotenv
VITE_BACKEND_TARGET=http://localhost:8080
```

启动开发服务器：

```bash
pnpm dev
```

启动后访问：

```text
http://localhost:5181/
```

前端通过 `vite.config.ts` 将以下请求代理到后端：

- `/api`
- `/agui`
- `/covers`

修改 `.env.local` 后需要重启 Vite 才会生效。

## 常用命令

```bash
pnpm dev             # 启动开发服务器
pnpm build           # 类型检查并构建生产文件
pnpm preview         # 预览构建产物
pnpm test            # 运行前端测试
pnpm python:check    # 检查本地 Python 运行环境
pnpm python:prepare  # 准备本地 Python 运行环境
```

构建产物默认输出到 `dist/`。如果只需要查看前端静态页面，可以先执行 `pnpm build`，再执行 `pnpm preview`；需要真实登录、智能体调用和数据读写时，仍然必须启动后端。

## 常见问题

### 页面能打开，但接口请求失败

检查：

1. 后端是否已经启动
2. `VITE_BACKEND_TARGET` 是否指向正确地址
3. 后端是否监听 `8080` 端口
4. 修改环境变量后是否重启了 Vite

### 依赖安装或启动时报缺少包

在项目根目录重新安装依赖：

```bash
pnpm install --frozen-lockfile
```

### 需要修改后端配置

不要在本 README 中补充后端启动命令。请直接查看并维护：

[`agent-platform-java/README.md`](agent-platform-java/README.md)

## 开发约定

- 前端接口使用相对路径，不要在业务代码中写死后端域名
- `src/legacy/` 中部分文件由脚本生成，修改前先确认对应的源文件和生成脚本
- `.env.local` 仅用于本地配置，不要提交密钥和机器专属配置
