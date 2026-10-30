import { reactive, ref } from 'vue'
import { api, readableMessage, setToken } from './api'
import type {
  CatalogItem,
  MqLog,
  PointsLog,
  SeataAudit,
  SeatItem,
  ShowInfo,
  ShowSession,
  Tab,
  TicketOrder,
  Toast,
  UserMessage,
  UserVO
} from './types'

export const DEMO_PASSWORD = 'Ticket@2026'

export const demoUsers = [
  { username: 'zhaolan', name: '赵岚', tier: '黑钻会员' },
  { username: 'linfeng', name: '林峰', tier: '金卡会员' },
  { username: 'suwan', name: '苏晚', tier: '普通会员' }
]

export const state = reactive({
  tab: 'theater' as Tab,
  username: '',
  user: null as UserVO | null,
  context: { userId: 0, username: '', traceId: '' },
  pointsLogs: [] as PointsLog[],
  messages: [] as UserMessage[],

  catalog: [] as CatalogItem[],
  showId: 0,
  sessionId: 0,
  show: null as ShowInfo | null,
  sessions: [] as ShowSession[],
  cache: { status: '', ttl: 0 },
  seats: [] as SeatItem[],
  selected: [] as number[],

  orders: [] as TicketOrder[],
  seataAudits: [] as SeataAudit[],
  mqLogs: [] as MqLog[],

  ticket: null as TicketOrder | null,
  messagesOpen: false,
  now: Date.now()
})

/* ---------------- toast ---------------- */

export const toasts = ref<Toast[]>([])
let toastSeq = 0

export function toast(type: Toast['type'], title: string, detail?: string) {
  const id = ++toastSeq
  toasts.value.push({ id, type, title, detail: detail ? readableMessage(detail) : undefined })
  setTimeout(() => {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }, 4800)
}

/* ---------------- user ---------------- */

export async function loginAs(username: string) {
  const res = await api('/api/user/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password: DEMO_PASSWORD })
  }, '')
  if (res.code !== 200) {
    toast('error', '登录失败', res.message)
    return
  }
  setToken(res.data.accessToken)
  state.username = username
  state.user = res.data.user
  state.selected = []
  await Promise.all([loadProfile(), loadOrders(), loadTelemetry()])
}

export async function loadProfile() {
  const res = await api('/api/user/profile')
  if (res.code !== 200) return
  state.user = res.data.user
  state.context = {
    userId: res.data.threadLocalUserId,
    username: res.data.threadLocalUsername,
    traceId: res.data.traceId
  }
  state.pointsLogs = res.data.pointsLogs || []
  state.messages = res.data.messages || []
}

export async function markMessageRead(id: number) {
  const msg = state.messages.find(m => m.id === id)
  if (!msg || msg.isRead) return
  msg.isRead = 1
  await api(`/api/user/messages/${id}/read`, { method: 'POST' })
}

export async function markAllMessagesRead() {
  const unreadList = state.messages.filter(m => !m.isRead)
  for (const m of unreadList) m.isRead = 1
  await Promise.all(unreadList.map(m => api(`/api/user/messages/${m.id}/read`, { method: 'POST' })))
}

/* ---------------- catalog & seats ---------------- */

export async function loadCatalog() {
  const res = await api<CatalogItem[]>('/api/show/public/list')
  if (res.code === 200) state.catalog = res.data || []
}

export async function selectShow(showId: number) {
  state.showId = showId
  state.selected = []
  const res = await api(`/api/show/public/detail/${showId}`)
  if (res.code !== 200 || !res.data?.show) return
  state.show = res.data.show
  state.sessions = res.data.sessions || []
  state.cache = { status: res.data.cacheStatus, ttl: res.data.ttlSeconds }
  if (state.sessions.length) await loadSeats(state.sessions[0].id)
}

export async function loadSeats(sessionId = state.sessionId) {
  if (!sessionId) return
  if (sessionId !== state.sessionId) state.selected = []
  state.sessionId = sessionId
  const res = await api(`/api/show/public/sessions/${sessionId}/seats`)
  if (res.code !== 200) return
  state.seats = res.data.seats || []
  const available = new Set(state.seats.filter(s => s.status === 'AVAILABLE').map(s => s.id))
  state.selected = state.selected.filter(id => available.has(id))
}

export async function rebuildCache() {
  await api(`/api/show/cache/evict/${state.showId}`, { method: 'POST' })
  const res = await api(`/api/show/public/detail/${state.showId}`)
  if (res.code !== 200) return
  state.cache = { status: res.data.cacheStatus, ttl: res.data.ttlSeconds }
  toast('info', '缓存已重建', `Redisson 互斥锁回源 MySQL，TTL ${res.data.ttlSeconds}s（含随机抖动）`)
}

export async function probePenetration() {
  const first = await api('/api/show/public/detail/99999')
  const second = await api('/api/show/public/detail/99999')
  toast(
    'info',
    '穿透防护生效',
    `不存在的演出 #99999：${first.data?.cacheStatus} → ${second.data?.cacheStatus}，第二次请求未查询数据库`
  )
}

/* ---------------- orders & telemetry ---------------- */

export async function loadOrders() {
  const res = await api<TicketOrder[]>('/api/order/my')
  if (res.code === 200) state.orders = res.data || []
}

export async function loadTelemetry() {
  const res = await api('/api/order/telemetry')
  if (res.code !== 200) return
  state.seataAudits = res.data.seataAudits || []
  state.mqLogs = res.data.mqLogs || []
}

export async function refreshAll() {
  await Promise.all([loadSeats(), loadOrders(), loadTelemetry(), loadProfile()])
}

export async function payOrder(order: TicketOrder) {
  const res = await api<TicketOrder>(`/api/order/${order.orderNo}/pay`, { method: 'POST' })
  if (res.code !== 200) {
    toast('error', '支付失败', res.message)
    await loadOrders()
    return
  }
  toast('success', '支付成功，已出票', `取票码 ${res.data.ticketCode}，积分与通知稍后到账`)
  state.ticket = res.data
  // 积分与出票由 MQ 异步消费，稍等片刻再刷新
  setTimeout(refreshAll, 800)
}

export async function cancelOrder(order: TicketOrder) {
  const res = await api(`/api/order/${order.orderNo}/cancel`, { method: 'POST' })
  if (res.code !== 200) {
    toast('error', '取消失败', res.message)
    return
  }
  toast('info', '订单已取消', '锁定的座位已释放')
  await refreshAll()
}
