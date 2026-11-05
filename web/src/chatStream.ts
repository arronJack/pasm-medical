/**
 * 坐诊会话的 SSE 客户端。
 *
 * ★ 为什么不用 `EventSource`：它**不能设置 Authorization 头**，只能把令牌塞进 URL
 *   （会进浏览器历史、代理日志、Referer）。这里用 `fetch` + `ReadableStream` 自己解析
 *   `text/event-stream`，既能带 Bearer 头，也能用 AbortController 精确关闭。
 *
 * ★ 断线重连：指数退避（0.8s → 8s 上限）。重连时带上 `afterId`，服务端会先补发
 *   断线期间的消息，所以「断线 → 重连」不会丢消息、也不会重复（上层按 id 去重）。
 */
import { getToken, type ChatMessage, type ChatSession } from './api'

export interface ChatStreamHandlers {
  onMessage: (m: ChatMessage) => void
  onSession: (s: ChatSession) => void
  onOpen?: () => void
  onError?: () => void
}

export function subscribeChat(
  sessionId: number,
  initialAfterId: number,
  h: ChatStreamHandlers,
): () => void {
  let closed = false
  let lastId = initialAfterId
  let ctrl: AbortController | null = null

  const loop = async () => {
    let backoff = 800
    while (!closed) {
      ctrl = new AbortController()
      try {
        const t = getToken()
        const r = await fetch(`/api/chat/${sessionId}/stream?afterId=${lastId}`, {
          headers: t ? { Authorization: 'Bearer ' + t } : {},
          signal: ctrl.signal,
        })
        if (!r.ok || !r.body) throw new Error(String(r.status))
        h.onOpen?.()
        backoff = 800

        const reader = r.body.getReader()
        const dec = new TextDecoder()
        let buf = ''
        while (!closed) {
          const { done, value } = await reader.read()
          if (done) break
          buf += dec.decode(value, { stream: true })
          let idx: number
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const chunk = buf.slice(0, idx)
            buf = buf.slice(idx + 2)
            let event = 'message'
            let data = ''
            for (const line of chunk.split('\n')) {
              if (line.startsWith('event:')) event = line.slice(6).trim()
              else if (line.startsWith('data:')) data += line.slice(5).trim()
            }
            if (!data) continue          // 心跳注释行（": ping"）
            try {
              const obj = JSON.parse(data)
              if (event === 'message') {
                const id = Number(obj?.id || 0)
                if (id > lastId) lastId = id
                h.onMessage(obj as ChatMessage)
              } else if (event === 'session') {
                h.onSession(obj as ChatSession)
              }
            } catch {
              /* 半包/坏包忽略，下一轮会补齐（服务端按 afterId 补发） */
            }
          }
        }
      } catch {
        if (closed) return
        h.onError?.()
      }
      if (closed) return
      await new Promise((r) => setTimeout(r, backoff))
      backoff = Math.min(8000, Math.round(backoff * 1.6))
    }
  }
  loop()

  return () => {
    closed = true
    ctrl?.abort()
  }
}
