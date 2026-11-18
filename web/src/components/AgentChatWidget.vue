<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Bot, Check, RotateCcw, Send, ShieldCheck, Sparkles, X } from 'lucide-vue-next'
import { getToken } from '../api'
import { refreshAll, state, toast } from '../store'
import type { AgentChatMessage, AgentConfirmationCard } from '../types'
import { money } from '../utils'

const isOpen = ref(false)
const inputText = ref('')
const sending = ref(false)
const scrollBox = ref<HTMLElement | null>(null)

function makeThreadId() {
  return `web-${state.user?.id || 'u'}-${Date.now().toString(36)}`
}

const threadId = ref(makeThreadId())
const messages = ref<AgentChatMessage[]>([])

const quickPrompts = [
  {
    tag: '双意图串联',
    text: '上海那场星际回响临时去不了能退票吗？可以的话帮我推荐两张 700 元以内的中间连座。'
  },
  {
    tag: '核价锁座中断',
    text: '太好了，就帮我锁定刚才推荐的这两张座位下单吧！'
  },
  {
    tag: 'Hybrid RAG 规则',
    text: '上海梅赛德斯文化中心坐几号线地铁？下雨天长柄雨伞能带进场吗？'
  },
  {
    tag: '盲区拒答验证',
    text: '买完票怎么开增值税专用发票并寄顺丰纸质票？'
  }
]

const intentLabels: Record<string, string> = {
  policy: '知识问答子图',
  purchase: '选座购票子图',
  order: '订单售后子图',
  chitchat: '对话引导'
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollBox.value) {
      scrollBox.value.scrollTop = scrollBox.value.scrollHeight
    }
  })
}

function resetConversation() {
  if (sending.value) return
  threadId.value = makeThreadId()
  messages.value = []
}

function escapeHtml(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function renderMarkdownSafe(text: string): string {
  if (!text) return ''
  let safe = escapeHtml(text)
  safe = safe.replace(/^###\s+(.+)$/gm, '<div class="mt-2 mb-1 font-semibold text-ink">$1</div>')
  safe = safe.replace(/\*\*(.+?)\*\*/g, '<strong class="font-semibold text-ink">$1</strong>')
  safe = safe.replace(
    /`?\[(POL-[A-Z]+-\d+)\]`?/g,
    '<span class="inline-block rounded bg-gold-soft px-1.5 py-0.5 font-mono text-[11px] font-medium text-gold">[$1]</span>'
  )
  safe = safe.replace(
    /`([^`]+)`/g,
    '<code class="rounded bg-ink/[0.06] px-1 py-0.5 font-mono text-[11.5px] text-ink">$1</code>'
  )
  return safe.replace(/\n/g, '<br/>')
}

async function consumeSsePost(
  path: string,
  payload: Record<string, any>,
  assistantMsg: AgentChatMessage
) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json'
  }
  const jwt = getToken()
  if (jwt) {
    headers.Authorization = `Bearer ${jwt}`
  }

  const resp = await fetch(path, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload)
  })

  if (!resp.ok || !resp.body) {
    let errText = `智能体服务暂不可用 (HTTP ${resp.status})`
    try {
      const errBody = await resp.json()
      errText = errBody.detail || errBody.message || errText
    } catch {
      // ignore non-json error body
    }
    throw new Error(errText)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let boundary = buffer.indexOf('\n\n')
    while (boundary !== -1) {
      const rawBlock = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')

      let eventType = 'message'
      let dataStr = ''
      for (const line of rawBlock.split('\n')) {
        if (line.startsWith('event:')) {
          eventType = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataStr += line.slice(5).trim()
        }
      }
      if (!dataStr) continue

      try {
        const parsed = JSON.parse(dataStr)
        if (eventType === 'route') {
          assistantMsg.intents = parsed.intents || []
        } else if (eventType === 'step') {
          assistantMsg.liveStepLabel = parsed.label || parsed.node
        } else if (eventType === 'token') {
          assistantMsg.content += parsed.delta || ''
        } else if (eventType === 'interrupt') {
          assistantMsg.confirmationCard = parsed.confirmationCard as AgentConfirmationCard
          assistantMsg.cardDecision = 'PENDING'
        } else if (eventType === 'done') {
          if (!assistantMsg.content && parsed.reply) {
            assistantMsg.content = parsed.reply
          }
          assistantMsg.status = parsed.status || 'COMPLETED'
          assistantMsg.intents = parsed.intents || assistantMsg.intents || []
          assistantMsg.subgraphs = parsed.subgraphs || []
          assistantMsg.citations = parsed.citations || []
          assistantMsg.toolSteps = parsed.toolSteps || []
          assistantMsg.latencyMs = parsed.latencyMs
          if (parsed.confirmationCard) {
            assistantMsg.confirmationCard = parsed.confirmationCard
            assistantMsg.cardDecision = assistantMsg.cardDecision || 'PENDING'
          }
        } else if (eventType === 'error') {
          throw new Error(parsed.message || '智能体流式响应异常')
        }
      } catch (e: any) {
        if (eventType === 'error') throw e
      }
      scrollToBottom()
    }
  }
}

async function sendMessage(preset?: string) {
  const text = (preset ?? inputText.value).trim()
  if (!text || sending.value) return

  if (!preset) {
    inputText.value = ''
  }

  messages.value.push({
    id: `u-${Date.now()}`,
    role: 'user',
    content: text
  })

  const assistantMsg: AgentChatMessage = {
    id: `a-${Date.now() + 1}`,
    role: 'assistant',
    content: '',
    streaming: true,
    liveStepLabel: '正在经过安全护栏与意图路由…',
    intents: [],
    citations: [],
    toolSteps: []
  }
  messages.value.push(assistantMsg)
  const reactiveAssistant = messages.value[messages.value.length - 1]
  sending.value = true
  scrollToBottom()

  try {
    await consumeSsePost(
      '/api/agent/chat/stream',
      { threadId: threadId.value, message: text },
      reactiveAssistant
    )
  } catch (err: any) {
    reactiveAssistant.status = 'ERROR'
    reactiveAssistant.content =
      err?.message || '智能体服务 (:8204) 未启动或网关连接异常，请先启动 ticket-agent 服务。'
  } finally {
    reactiveAssistant.streaming = false
    reactiveAssistant.liveStepLabel = undefined
    sending.value = false
    scrollToBottom()
  }
}

async function handleCardDecision(msg: AgentChatMessage, confirmed: boolean) {
  if (!msg.confirmationCard || msg.cardDecision !== 'PENDING' || sending.value) {
    return
  }

  msg.cardDecision = 'SUBMITTING'
  sending.value = true

  const receiptMsg: AgentChatMessage = {
    id: `r-${Date.now()}`,
    role: 'assistant',
    content: '',
    streaming: true,
    liveStepLabel: confirmed ? '正在携带幂等键调用 Java 订单服务…' : '正在释放挂起确认状态…'
  }
  messages.value.push(receiptMsg)
  const reactiveReceipt = messages.value[messages.value.length - 1]
  scrollToBottom()

  try {
    await consumeSsePost(
      '/api/agent/confirm/stream',
      { threadId: threadId.value, confirmed },
      reactiveReceipt
    )
    msg.cardDecision = confirmed ? 'CONFIRMED' : 'REJECTED'

    if (confirmed) {
      await refreshAll()
      toast('success', 'AI 助手操作已同步', '座位图与我的订单列表已实时刷新')
    }
  } catch (err: any) {
    msg.cardDecision = 'PENDING'
    reactiveReceipt.status = 'ERROR'
    reactiveReceipt.content = err?.message || '确认请求处理失败，可稍后重试（幂等键保障不会重复创单）'
  } finally {
    reactiveReceipt.streaming = false
    reactiveReceipt.liveStepLabel = undefined
    sending.value = false
    scrollToBottom()
  }
}
</script>

<template>
  <!-- Floating Launcher Pill -->
  <div class="fixed bottom-6 right-6 z-30">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0 translate-y-3 scale-95"
      leave-active-class="transition duration-150 ease-in"
      leave-to-class="opacity-0 translate-y-3 scale-95"
    >
      <section
        v-if="isOpen"
        class="mb-3 flex h-[620px] max-h-[calc(100vh-100px)] w-[410px] max-w-[calc(100vw-32px)] flex-col overflow-hidden rounded-3xl border border-line bg-white shadow-float"
      >
        <!-- Header -->
        <header class="flex items-center justify-between border-b border-line bg-paper/70 px-5 py-4">
          <div class="flex items-center gap-3">
            <div class="grid h-9 w-9 place-items-center rounded-2xl bg-ink text-white">
              <Bot class="h-4 w-4" />
            </div>
            <div>
              <div class="flex items-center gap-2">
                <h3 class="text-sm font-semibold tracking-tight text-ink">AI 购票助手</h3>
                <span class="rounded-full bg-sage-soft px-2 py-0.5 text-[10px] font-medium text-sage">
                  主图 + 3 子图
                </span>
              </div>
              <p class="mt-0.5 text-[11px] text-ink-mute">
                Hybrid RAG · 服务端核价 · Interrupt 确认门控
              </p>
            </div>
          </div>

          <div class="flex items-center gap-1">
            <button
              class="grid h-8 w-8 place-items-center rounded-full text-ink-mute transition hover:bg-white hover:text-ink"
              title="开启新会话"
              :disabled="sending"
              @click="resetConversation"
            >
              <RotateCcw class="h-3.5 w-3.5" />
            </button>
            <button
              class="grid h-8 w-8 place-items-center rounded-full text-ink-mute transition hover:bg-white hover:text-ink"
              aria-label="收起助手"
              @click="isOpen = false"
            >
              <X class="h-4 w-4" />
            </button>
          </div>
        </header>

        <!-- Chat Body -->
        <div ref="scrollBox" class="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          <!-- Welcome & Quick Prompts -->
          <div v-if="!messages.length" class="space-y-4 py-2">
            <div class="rounded-2xl border border-line bg-paper/70 p-4">
              <p class="text-xs font-medium text-ink">
                你好，{{ state.user?.nickname || state.username }}！同一对话框支持多意图协同：
              </p>
              <p class="mt-1.5 text-xs leading-relaxed text-ink-soft">
                问退改/地铁自动走<strong>知识问答子图</strong>，找场次选连座走<strong>选座购票子图</strong>，锁座与取消前均由<strong>Java 服务端权威核价</strong>并弹出确认卡片。
              </p>
            </div>

            <div class="space-y-2">
              <p class="eyebrow">一键体验典型链路</p>
              <button
                v-for="item in quickPrompts"
                :key="item.tag"
                class="group block w-full rounded-2xl border border-line bg-white px-3.5 py-2.5 text-left transition hover:border-ink/35 hover:bg-paper/50"
                @click="sendMessage(item.text)"
              >
                <span class="inline-block rounded-full bg-ink/[0.06] px-2 py-0.5 text-[10px] font-medium text-ink-soft">
                  {{ item.tag }}
                </span>
                <p class="mt-1 text-xs leading-relaxed text-ink group-hover:text-black">
                  {{ item.text }}
                </p>
              </button>
            </div>
          </div>

          <!-- Messages -->
          <template v-for="msg in messages" :key="msg.id">
            <!-- User Bubble -->
            <div v-if="msg.role === 'user'" class="flex justify-end">
              <div class="max-w-[84%] rounded-2xl rounded-br-sm bg-ink px-4 py-2.5 text-xs leading-relaxed text-white">
                {{ msg.content }}
              </div>
            </div>

            <!-- Assistant Bubble -->
            <div v-else class="space-y-2.5">
              <!-- Route & Subgraph Trace Pills -->
              <div
                v-if="(msg.intents && msg.intents.length) || msg.liveStepLabel || (msg.citations && msg.citations.length)"
                class="flex flex-wrap items-center gap-1.5"
              >
                <span
                  v-for="it in msg.intents"
                  :key="it"
                  class="rounded-full border border-line bg-paper px-2 py-0.5 text-[10px] font-medium text-ink-soft"
                >
                  {{ intentLabels[it] || it }}
                </span>
                <span
                  v-if="msg.liveStepLabel"
                  class="inline-flex items-center gap-1 rounded-full bg-brick-soft px-2.5 py-0.5 text-[10px] font-medium text-brick"
                >
                  <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-brick" />
                  {{ msg.liveStepLabel }}
                </span>
                <span
                  v-if="!msg.streaming && msg.latencyMs"
                  class="font-mono text-[10px] text-ink-mute tabular"
                >
                  {{ msg.latencyMs }}ms
                </span>
              </div>

              <!-- Text Content -->
              <div
                v-if="msg.content || msg.streaming"
                class="rounded-2xl rounded-bl-sm border px-4 py-3 text-xs leading-relaxed"
                :class="
                  msg.status === 'BLOCKED' || msg.status === 'ERROR'
                    ? 'border-brick/30 bg-brick-soft/40 text-ink'
                    : 'border-line bg-paper/75 text-ink'
                "
              >
                <div v-if="msg.content" v-html="renderMarkdownSafe(msg.content)" />
                <div v-else class="flex items-center gap-1.5 text-ink-mute">
                  <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-ink-mute" />
                  <span>正在思考与检索…</span>
                </div>
              </div>

              <!-- Interrupt Confirmation Ticket Card -->
              <div
                v-if="msg.confirmationCard"
                class="overflow-hidden rounded-2xl border border-ink/20 bg-white shadow-soft"
              >
                <div class="flex items-center justify-between border-b border-dashed border-line bg-paper px-4 py-2.5">
                  <span class="flex items-center gap-1.5 text-xs font-semibold text-ink">
                    <ShieldCheck class="h-3.5 w-3.5 text-sage" />
                    {{ msg.confirmationCard.title }}
                  </span>
                  <span
                    v-if="msg.confirmationCard.isMock"
                    class="rounded bg-gold-soft px-1.5 py-0.5 font-mono text-[10px] text-gold"
                  >
                    DEV_MOCK
                  </span>
                  <span v-else class="font-mono text-[10px] text-sage">Java 实时核价</span>
                </div>

                <div class="space-y-2 px-4 py-3 text-xs">
                  <p class="font-medium text-ink">
                    {{ msg.confirmationCard.args.show_title }}
                  </p>
                  <p v-if="msg.confirmationCard.args.session_title" class="text-ink-soft">
                    {{ msg.confirmationCard.args.session_title }}
                  </p>

                  <div
                    v-if="msg.confirmationCard.args.seat_labels && msg.confirmationCard.args.seat_labels.length"
                    class="flex flex-wrap gap-1.5 pt-0.5"
                  >
                    <span
                      v-for="lb in msg.confirmationCard.args.seat_labels"
                      :key="lb"
                      class="rounded-full border border-line bg-paper px-2.5 py-0.5 text-[11px] text-ink-soft"
                    >
                      {{ lb }}
                    </span>
                  </div>

                  <p v-if="msg.confirmationCard.args.order_no" class="font-mono text-[11px] text-ink-soft">
                    订单号：{{ msg.confirmationCard.args.order_no }}
                  </p>

                  <div class="flex items-baseline justify-between border-t border-line pt-2.5">
                    <span class="text-[11px] text-ink-mute">服务端核定金额</span>
                    <span class="text-base font-semibold text-ink tabular">
                      ¥{{ money(Number(msg.confirmationCard.args.total_amount || 0)) }}
                    </span>
                  </div>
                  <p class="truncate font-mono text-[10px] text-ink-mute">
                    幂等键: {{ msg.confirmationCard.idempotencyKey }}
                  </p>

                  <!-- Single-Click Guarded Buttons -->
                  <div v-if="msg.cardDecision === 'PENDING'" class="flex gap-2 pt-2">
                    <button
                      class="btn-ghost h-8 flex-1 px-0 text-xs"
                      :disabled="sending"
                      @click="handleCardDecision(msg, false)"
                    >
                      暂不执行
                    </button>
                    <button
                      class="btn-primary h-8 flex-[1.4] px-0 text-xs"
                      :class="msg.confirmationCard.actionType === 'CANCEL_ORDER' ? '!bg-brick hover:!bg-[#9c3a22]' : ''"
                      :disabled="sending"
                      @click="handleCardDecision(msg, true)"
                    >
                      {{ msg.confirmationCard.actionType === 'CREATE_ORDER' ? '确认锁座下单' : '确认取消订单' }}
                    </button>
                  </div>

                  <div
                    v-else-if="msg.cardDecision === 'SUBMITTING'"
                    class="pt-1.5 text-center text-[11px] text-ink-mute"
                  >
                    正在提交确认指令…
                  </div>

                  <div
                    v-else
                    class="flex items-center justify-center gap-1.5 rounded-xl bg-paper py-1.5 text-[11px] font-medium"
                    :class="msg.cardDecision === 'CONFIRMED' ? 'text-sage' : 'text-ink-mute'"
                  >
                    <Check v-if="msg.cardDecision === 'CONFIRMED'" class="h-3.5 w-3.5" />
                    {{ msg.cardDecision === 'CONFIRMED' ? '已确认提交（按钮已单次锁定防重）' : '已取消该次操作' }}
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- Input Bar -->
        <form
          class="flex items-center gap-2 border-t border-line bg-white px-4 py-3"
          @submit.prevent="sendMessage()"
        >
          <input
            v-model="inputText"
            type="text"
            :disabled="sending"
            placeholder="问规则、找演出、选连座或管理订单…"
            class="h-10 flex-1 rounded-full border border-line bg-paper/70 px-4 text-xs text-ink placeholder:text-ink-mute focus:border-ink/40 focus:bg-white focus:outline-none disabled:opacity-50"
          />
          <button
            type="submit"
            :disabled="!inputText.trim() || sending"
            class="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-ink text-white transition hover:bg-black disabled:cursor-not-allowed disabled:opacity-35"
            aria-label="发送"
          >
            <Send class="h-3.5 w-3.5" />
          </button>
        </form>
      </section>
    </Transition>

    <!-- Bottom-Right Trigger Button -->
    <div class="flex justify-end">
      <button
        class="inline-flex h-12 items-center gap-2.5 rounded-full bg-ink px-5 text-xs font-medium text-white shadow-float transition hover:scale-[1.02] hover:bg-black"
        @click="isOpen = !isOpen"
      >
        <Sparkles class="h-4 w-4 text-amber-300" />
        <span>{{ isOpen ? '收起助手' : 'AI 购票助手' }}</span>
      </button>
    </div>
  </div>
</template>
