/// <reference types="vite/client" />

/** Vue 单文件组件的模块声明（缺失时 TS 找不到 .vue 导入） */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

/** 补 Vite 的环境变量类型，让 import.meta.env.XXX 有类型 */
interface ImportMetaEnv {
  readonly VITE_BACKEND_TARGET?: string
  readonly VITE_SMYY_AGENT_NAME?: string
  readonly VITE_SMYY_AGENT_ID?: string
  readonly VITE_SANDBOX_MODE?: string
  readonly VITE_SANDBOX_ARTIFACTS_POLL?: string
  readonly VITE_SANDBOX_ATTACH_MAX_MB?: string
  readonly VITE_SANDBOX_EXEC_STRUCTURED?: string
  readonly VITE_SANDBOX_RETENTION_DAYS?: string
  readonly VITE_AUTH_MODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
