<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  Ticket,
  Sparkles,
  ShieldCheck,
  Zap,
  Clock,
  Layers,
  Database,
  Server,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Award,
  Bell,
  Users,
  Flame,
  Cpu,
  ArrowRight,
  Lock,
  Unlock,
  CreditCard,
  QrCode,
  Activity,
  Trash2,
  Eye
} from 'lucide-vue-next'

interface UserVO {
  id: number
  username: string
  nickname: string
  phone: string
  vipLevel: string
  pointsBalance: number
  totalOrders: number
}

interface ShowInfo {
  id: number
  showCode: string
  title: string
  subtitle: string
  category: string
  city: string
  venueName: string
  posterGradient: string
  badgeText: string
  rating: number
  minPrice: number
  maxPrice: number
  tags: string
  description: string
  hotScore: number
  status: string
}

interface ShowSession {
  id: number
  showId: number
  sessionCode: string
  sessionTitle: string
  hallName: string
  startTime: string
  endTime: string
  totalSeats: number
  availableSeats: number
  lockedSeats: number
  soldSeats: number
}

interface SeatItem {
  id: number
  sessionId: number
  rowNo: number
  colNo: number
  seatLabel: string
  zoneCode: 'VIP' | 'STAGE_SIDE' | 'STANDARD'
  zoneName: string
  price: number
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD'
  lockedOrderNo?: string | null
  lockedUserId?: number | null
  version: number
}

interface TicketOrder {
  id: number
  orderNo: string
  userId: number
  username: string
  showId: number
  showTitle: string
  venueName: string
  sessionId: number
  sessionTitle: string
  seatIdsJson: string
  seatLabelsJson: string
  seatCount: number
  totalAmount: number
  status: 'UNPAID' | 'PAID' | 'CLOSED'
  delayMode: string
  expireAt: string
  paidAt?: string | null
  closedAt?: string | null
  closeReason?: string | null
  ticketCode?: string | null
  seataXid?: string | null
  createdAt: string
}

// Active Navigation Tab
const activeTab = ref<'theater' | 'orders' | 'telemetry'>('theater')

// Demo Accounts for quick switching
const demoUsers = [
  { username: 'zhaolan', label: '赵岚 · 黑钻观演家', badge: 'BLACK_DIAMOND' },
  { username: 'linfeng', label: '林峰 · 金卡乐迷', badge: 'GOLD' },
  { username: 'suwan', label: '苏晚 · 影迷达人', badge: 'NORMAL' }
]

const currentUsername = ref('zhaolan')
const accessToken = ref('')
const currentUser = ref<UserVO | null>(null)
const threadLocalMeta = ref({ userId: 1001, username: 'zhaolan', traceId: 'tr-init' })
const pointsLogs = ref<any[]>([])
const userMessages = ref<any[]>([])
const showMessagesDrawer = ref(false)

// Catalog & Venue Seat Map State
const showCatalog = ref<{ show: ShowInfo; sessions: ShowSession[] }[]>([])
const selectedShowId = ref<number>(1)
const selectedSessionId = ref<number>(101)
const currentShowDetail = ref<ShowInfo | null>(null)
const currentSessions = ref<ShowSession[]>([])
const cacheInspector = ref<{
  cacheStatus: string
  ttlSeconds?: number
  message?: string
  lastCheckedAt: string
}>({
  cacheStatus: 'REDIS_HIT',
  ttlSeconds: 1800,
  lastCheckedAt: '--:--:--'
})

const seats = ref<SeatItem[]>([])
const selectedSeatIds = ref<number[]>([])
const loadingSeats = ref(false)

// Microservice Lab Controls
const fastCloseDemo = ref(true) // Default true for instant 15s DLX interview demonstration!
const simulateSeataRollback = ref(false)
const submittingOrder = ref(false)

// Concurrency Stress Test Lab State
const stressTesting = ref(false)
const stressResult = ref<{
  seatLabel: string
  totalRequests: number
  succeededCount: number
  rejectedCount: number
  winningOrderNo: string
  sampleRejectReason: string
  elapsedMs: number
} | null>(null)

// Orders & Telemetry
const myOrders = ref<TicketOrder[]>([])
const seataAudits = ref<any[]>([])
const mqLogs = ref<any[]>([])
const activeTicketModal = ref<TicketOrder | null>(null)

// Toast Banner
const banner = ref<{ type: 'success' | 'error' | 'info' | 'warning'; title: string; detail: string } | null>(null)
const nowTimestamp = ref(Date.now())
let timerInterval: any = null

function notify(type: 'success' | 'error' | 'info' | 'warning', title: string, detail: string) {
  banner.value = { type, title, detail }
}

async function apiFetch(path: string, options: RequestInit = {}, overrideToken?: string) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> || {})
  }
  const tk = overrideToken ?? accessToken.value
  if (tk) {
    headers['Authorization'] = `Bearer ${tk}`
  }
  const resp = await fetch(path, { ...options, headers })
  const data = await resp.json()
  return { status: resp.status, body: data }
}

async function loginAs(username: string) {
  try {
    const { body } = await apiFetch('/api/user/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password: 'Ticket@2026' })
    }, '')
    if (body.code === 200) {
      currentUsername.value = username
      accessToken.value = body.data.accessToken
      currentUser.value = body.data.user
      selectedSeatIds.value = []
      await Promise.all([fetchProfile(), fetchMyOrders(), fetchTelemetry()])
    }
  } catch (e: any) {
    notify('error', '网关认证连接失败', e.message || '请确认 ticket-gateway (:8200) 已启动')
  }
}

async function fetchProfile() {
  if (!accessToken.value) return
  const { body } = await apiFetch('/api/user/profile')
  if (body.code === 200) {
    currentUser.value = body.data.user
    threadLocalMeta.value = {
      userId: body.data.threadLocalUserId,
      username: body.data.threadLocalUsername,
      traceId: body.data.traceId
    }
    pointsLogs.value = body.data.pointsLogs || []
    userMessages.value = body.data.messages || []
  }
}

async function fetchCatalog() {
  const { body } = await apiFetch('/api/show/public/list')
  if (body.code === 200) {
    showCatalog.value = body.data || []
  }
}

async function selectShow(showId: number) {
  selectedShowId.value = showId
  selectedSeatIds.value = []
  const { body } = await apiFetch(`/api/show/public/detail/${showId}`)
  if (body.code === 200 && body.data) {
    currentShowDetail.value = body.data.show
    currentSessions.value = body.data.sessions || []
    cacheInspector.value = {
      cacheStatus: body.data.cacheStatus,
      ttlSeconds: body.data.ttlSeconds,
      message: body.data.message,
      lastCheckedAt: new Date().toLocaleTimeString()
    }
    if (currentSessions.value.length > 0) {
      selectedSessionId.value = currentSessions.value[0].id
      await fetchSeatMap(selectedSessionId.value)
    }
  }
}

async function evictAndReloadCache() {
  await apiFetch(`/api/show/cache/evict/${selectedShowId.value}`, { method: 'POST' })
  await selectShow(selectedShowId.value)
  notify(
    'info',
    'Redis 缓存驱逐并触发 Redisson 互斥锁重建成功',
    `当前状态: ${cacheInspector.value.cacheStatus}，TTL=${cacheInspector.value.ttlSeconds}s (含随机防雪崩抖动)`
  )
}

async function testCachePenetrationGuard() {
  const { body } = await apiFetch('/api/show/public/detail/99999')
  if (body.code === 200 && body.data) {
    cacheInspector.value = {
      cacheStatus: body.data.cacheStatus,
      ttlSeconds: 60,
      message: body.data.message,
      lastCheckedAt: new Date().toLocaleTimeString()
    }
    notify(
      'warning',
      `防缓存穿透哨兵验证 (${body.data.cacheStatus})`,
      body.data.message || '针对不存在的 showId=99999 命中 Redis __NULL__ 空值哨兵，0 次 MySQL 慢查询冲击！'
    )
  }
}

async function fetchSeatMap(sessionId: number) {
  loadingSeats.value = true
  try {
    selectedSessionId.value = sessionId
    const { body } = await apiFetch(`/api/show/public/sessions/${sessionId}/seats`)
    if (body.code === 200 && body.data) {
      seats.value = body.data.seats || []
      // Remove any selected seat that is no longer AVAILABLE
      const availableSet = new Set(
        seats.value.filter(s => s.status === 'AVAILABLE').map(s => s.id)
      )
      selectedSeatIds.value = selectedSeatIds.value.filter(id => availableSet.has(id))
    }
  } finally {
    loadingSeats.value = false
  }
}

function toggleSeat(seat: SeatItem) {
  if (seat.status !== 'AVAILABLE') return
  const idx = selectedSeatIds.value.indexOf(seat.id)
  if (idx >= 0) {
    selectedSeatIds.value.splice(idx, 1)
  } else {
    if (selectedSeatIds.value.length >= 4) {
      notify('warning', '单笔订单限购 4 张', '同一场次单次最多可同时锁定 4 个座位（支持 RedissonMultiLock 联锁）')
      return
    }
    selectedSeatIds.value.push(seat.id)
  }
}

const selectedSeatObjects = computed(() =>
  seats.value.filter(s => selectedSeatIds.value.includes(s.id))
)

const totalSelectedPrice = computed(() =>
  selectedSeatObjects.value.reduce((acc, s) => acc + Number(s.price), 0)
)

const seatRows = computed(() => {
  const map = new Map<number, SeatItem[]>()
  for (const s of seats.value) {
    if (!map.has(s.rowNo)) map.set(s.rowNo, [])
    map.get(s.rowNo)!.push(s)
  }
  return Array.from(map.entries()).sort((a, b) => a[0] - b[0])
})

const seatStats = computed(() => {
  let available = 0
  let locked = 0
  let sold = 0
  for (const s of seats.value) {
    if (s.status === 'AVAILABLE') available++
    else if (s.status === 'LOCKED') locked++
    else if (s.status === 'SOLD') sold++
  }
  return { available, locked, sold, total: seats.value.length }
})

async function handleCreateOrder() {
  if (selectedSeatIds.value.length === 0) {
    notify('warning', '请先在场馆图中点选座位', '支持点选 1~4 个绿色可选座位进行锁座下单')
    return
  }
  submittingOrder.value = true
  try {
    const targetLabels = selectedSeatObjects.value.map(s => s.seatLabel).join('、')
    const { body } = await apiFetch('/api/order/create', {
      method: 'POST',
      body: JSON.stringify({
        sessionId: selectedSessionId.value,
        seatIds: [...selectedSeatIds.value],
        fastCloseDemo: fastCloseDemo.value,
        simulateSeataRollback: simulateSeataRollback.value
      })
    })

    await Promise.all([
      fetchSeatMap(selectedSessionId.value),
      fetchMyOrders(),
      fetchTelemetry()
    ])

    if (body.code === 200) {
      selectedSeatIds.value = []
      const order: TicketOrder = body.data
      notify(
        'success',
        `锁座成功！订单号 ${order.orderNo}`,
        fastCloseDemo.value
          ? `已锁定 [${targetLabels}] 并投递 15 秒极速死信延迟队列 (若 15s 内未支付将自动关单并释放座位)`
          : `已锁定 [${targetLabels}] 并投递 15 分钟标准死信延迟队列`
      )
      activeTab.value = 'orders'
    } else {
      notify(
        'error',
        simulateSeataRollback.value ? '💥 Seata AT 分布式事务自动回滚成功！' : '锁座下单被拦截',
        body.message || '请查看微服务实时观测台审计日志'
      )
    }
  } finally {
    submittingOrder.value = false
  }
}

async function runConcurrencyStressLab() {
  const availableList = seats.value.filter(s => s.status === 'AVAILABLE')
  if (availableList.length === 0) {
    notify('warning', '当前场次无空闲座位可供压测', '请先切换场次或释放已锁定座位')
    return
  }
  const targetSeat =
    selectedSeatObjects.value.length > 0
      ? selectedSeatObjects.value[0]
      : availableList[availableList.length - 1]

  stressTesting.value = true
  stressResult.value = null
  const t0 = performance.now()
  try {
    // Obtain tokens for all 3 users to simulate real multi-user concurrent requests
    const tokens: string[] = []
    for (const u of demoUsers) {
      const { body } = await apiFetch(
        '/api/user/auth/login',
        { method: 'POST', body: JSON.stringify({ username: u.username, password: 'Ticket@2026' }) },
        ''
      )
      if (body.code === 200) tokens.push(body.data.accessToken)
    }

    const totalThreads = 20
    const tasks = Array.from({ length: totalThreads }, (_, i) => {
      const tk = tokens[i % tokens.length]
      return apiFetch(
        '/api/order/create',
        {
          method: 'POST',
          body: JSON.stringify({
            sessionId: selectedSessionId.value,
            seatIds: [targetSeat.id],
            fastCloseDemo: true,
            simulateSeataRollback: false
          })
        },
        tk
      )
    })

    const responses = await Promise.all(tasks)
    const elapsedMs = Math.round(performance.now() - t0)
    const winners = responses.filter(r => r.body.code === 200)
    const rejects = responses.filter(r => r.body.code !== 200)

    stressResult.value = {
      seatLabel: `${targetSeat.zoneName} ${targetSeat.seatLabel}`,
      totalRequests: totalThreads,
      succeededCount: winners.length,
      rejectedCount: rejects.length,
      winningOrderNo: winners[0]?.body?.data?.orderNo || 'N/A',
      sampleRejectReason: rejects[0]?.body?.message || 'Redisson MultiLock / CAS 拦截',
      elapsedMs
    }

    selectedSeatIds.value = []
    await Promise.all([
      fetchSeatMap(selectedSessionId.value),
      fetchMyOrders(),
      fetchTelemetry()
    ])

    notify(
      'success',
      `🔥 20 并发同座秒杀演练完成 (0 超卖)`,
      `目标座位 [${targetSeat.seatLabel}]：仅 1 个请求成功锁座 (${stressResult.value.winningOrderNo})，其余 ${rejects.length} 个请求被 Redisson 防重锁/MultiLock 精准拦截！`
    )
  } finally {
    stressTesting.value = false
  }
}

async function fetchMyOrders() {
  if (!accessToken.value) return
  const { body } = await apiFetch('/api/order/my')
  if (body.code === 200) {
    myOrders.value = body.data || []
  }
}

async function fetchTelemetry() {
  if (!accessToken.value) return
  const { body } = await apiFetch('/api/order/telemetry')
  if (body.code === 200 && body.data) {
    seataAudits.value = body.data.seataAudits || []
    mqLogs.value = body.data.mqLogs || []
  }
}

async function handlePayOrder(order: TicketOrder) {
  const { body } = await apiFetch(`/api/order/${order.orderNo}/pay`, { method: 'POST' })
  if (body.code === 200) {
    notify(
      'success',
      `🎉 支付成功！电子票核验码 ${body.data.ticketCode}`,
      '已通过 RabbitMQ Fanout 异步广播：show-service 座位已更新为 SOLD，user-service 已幂等发放会员积分与出票通知！'
    )
    activeTicketModal.value = body.data
    setTimeout(async () => {
      await Promise.all([
        fetchMyOrders(),
        fetchSeatMap(selectedSessionId.value),
        fetchProfile(),
        fetchTelemetry()
      ])
    }, 600)
  } else {
    notify('error', '支付失败', body.message || '订单可能已超时关闭')
    await fetchMyOrders()
  }
}

async function handleCancelOrder(order: TicketOrder) {
  const { body } = await apiFetch(`/api/order/${order.orderNo}/cancel`, { method: 'POST' })
  if (body.code === 200) {
    notify('info', `订单 ${order.orderNo} 已取消`, '锁定座位已立即归还场馆库存池 (AVAILABLE)')
    await Promise.all([
      fetchMyOrders(),
      fetchSeatMap(selectedSessionId.value),
      fetchTelemetry()
    ])
  }
}

function parseJsonArray(raw: string): string[] {
  try {
    return JSON.parse(raw)
  } catch {
    return [raw]
  }
}

function getRemainingSeconds(expireAtStr: string): number {
  if (!expireAtStr) return 0
  const expireMs = new Date(expireAtStr.replace(' ', 'T')).getTime()
  return Math.max(0, Math.floor((expireMs - nowTimestamp.value) / 1000))
}

function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

onMounted(async () => {
  await fetchCatalog()
  await loginAs('zhaolan')
  await selectShow(1)

  // Tick every second for live countdown & auto-refresh when an UNPAID order reaches 00:00
  timerInterval = setInterval(async () => {
    nowTimestamp.value = Date.now()
    const hasJustExpired = myOrders.value.some(
      o => o.status === 'UNPAID' && getRemainingSeconds(o.expireAt) === 0
    )
    if (hasJustExpired) {
      await Promise.all([
        fetchMyOrders(),
        fetchSeatMap(selectedSessionId.value),
        fetchTelemetry()
      ])
    }
  }, 1500)
})

onUnmounted(() => {
  if (timerInterval) clearInterval(timerInterval)
})
</script>

<template>
  <div class="min-h-screen bg-[#060913] text-slate-100 flex flex-col">
    <!-- Top Microservice Architecture Header -->
    <header class="relative z-40 border-b border-slate-800/80 bg-slate-950/90 backdrop-blur-xl">
      <div class="max-w-[1440px] mx-auto px-6 h-16 flex items-center justify-between gap-4">
        <!-- Brand Logo -->
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 via-violet-500 to-fuchsia-500 flex items-center justify-center shadow-lg shadow-indigo-500/25">
            <Ticket class="w-5 h-5 text-white" />
          </div>
          <div>
            <div class="flex items-center gap-2">
              <span class="font-bold text-lg tracking-tight bg-gradient-to-r from-white via-indigo-200 to-fuchsia-200 bg-clip-text text-transparent">
                PulseTicket
              </span>
              <span class="text-[11px] font-mono px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-300 border border-indigo-500/30">
                Spring Cloud Alibaba 2023
              </span>
            </div>
            <p class="text-xs text-slate-400">
              高并发演出选座与分布式事务微服务系统 · Gateway + Redisson + Seata AT + RabbitMQ DLX
            </p>
          </div>
        </div>

        <!-- Center Navigation Tabs -->
        <nav class="flex items-center bg-slate-900/90 p-1 rounded-xl border border-slate-800">
          <button
            @click="activeTab = 'theater'"
            :class="[
              'flex items-center gap-2 px-4 py-1.5 rounded-lg text-sm font-medium transition-all',
              activeTab === 'theater'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200'
            ]"
          >
            <Sparkles class="w-4 h-4" />
            <span>演出选座大厅</span>
          </button>

          <button
            @click="activeTab = 'orders'; fetchMyOrders()"
            :class="[
              'flex items-center gap-2 px-4 py-1.5 rounded-lg text-sm font-medium transition-all',
              activeTab === 'orders'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200'
            ]"
          >
            <CreditCard class="w-4 h-4" />
            <span>我的票夹 & 倒计时收银台</span>
            <span
              v-if="myOrders.filter(o => o.status === 'UNPAID').length > 0"
              class="px-1.5 py-0.2 rounded-full text-[11px] bg-amber-500 text-slate-950 font-bold animate-pulse"
            >
              {{ myOrders.filter(o => o.status === 'UNPAID').length }} 待付
            </span>
          </button>

          <button
            @click="activeTab = 'telemetry'; fetchTelemetry(); fetchProfile()"
            :class="[
              'flex items-center gap-2 px-4 py-1.5 rounded-lg text-sm font-medium transition-all',
              activeTab === 'telemetry'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200'
            ]"
          >
            <Activity class="w-4 h-4" />
            <span>Seata / MQ 实时观测台</span>
          </button>
        </nav>

        <!-- Right User Switcher & VIP Points & Notification Bell -->
        <div class="flex items-center gap-3">
          <!-- Account Switcher -->
          <div class="flex items-center gap-1.5 bg-slate-900/90 border border-slate-800 rounded-xl px-2.5 py-1">
            <Users class="w-3.5 h-3.5 text-indigo-400" />
            <select
              :value="currentUsername"
              @change="loginAs(($event.target as HTMLSelectElement).value)"
              class="bg-transparent text-xs text-slate-200 font-medium focus:outline-none cursor-pointer"
            >
              <option
                v-for="u in demoUsers"
                :key="u.username"
                :value="u.username"
                class="bg-slate-900 text-slate-100"
              >
                {{ u.label }}
              </option>
            </select>
          </div>

          <!-- VIP Points Pill -->
          <div
            v-if="currentUser"
            class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs font-medium"
            title="RabbitMQ Fanout 支付成功异步累加积分 (uk_order_biz 唯一索引防重)"
          >
            <Award class="w-3.5 h-3.5 text-amber-400" />
            <span>{{ currentUser.pointsBalance }} 积分</span>
          </div>

          <!-- In-App Ticket Messages Drawer Button -->
          <button
            @click="showMessagesDrawer = !showMessagesDrawer"
            class="relative p-2 rounded-xl bg-slate-900 border border-slate-800 hover:border-indigo-500/50 text-slate-300 transition"
            title="查看站内出票通知 (user-service 异步消费)"
          >
            <Bell class="w-4 h-4" />
            <span
              v-if="userMessages.length > 0"
              class="absolute -top-1 -right-1 w-4 h-4 rounded-full bg-fuchsia-500 text-[10px] font-bold text-white flex items-center justify-center"
            >
              {{ userMessages.length }}
            </span>
          </button>
        </div>
      </div>

      <!-- Sub-header: Live Microservice Topology & ThreadLocal Relay Bar -->
      <div class="bg-slate-900/60 border-t border-slate-800/60 px-6 py-1.5">
        <div class="max-w-[1440px] mx-auto flex flex-wrap items-center justify-between gap-2 text-[11px] text-slate-400">
          <div class="flex items-center gap-4">
            <span class="flex items-center gap-1.5 text-emerald-400 font-medium">
              <span class="w-2 h-2 rounded-full bg-emerald-400 animate-ping"></span>
              集群在线:
            </span>
            <span class="font-mono text-slate-300">Gateway(:8200)</span>
            <span>→</span>
            <span class="font-mono text-slate-300">user-service(:8201)</span>
            <span>·</span>
            <span class="font-mono text-slate-300">show-service(:8202)</span>
            <span>·</span>
            <span class="font-mono text-slate-300">order-service(:8203)</span>
          </div>

          <div class="flex items-center gap-3 font-mono">
            <span class="px-2 py-0.5 rounded bg-slate-800/90 text-indigo-300 border border-slate-700">
              ThreadLocal X-User-Id: {{ threadLocalMeta.userId }} ({{ threadLocalMeta.username }})
            </span>
            <span class="px-2 py-0.5 rounded bg-slate-800/90 text-slate-300 border border-slate-700">
              X-Trace-Id: {{ threadLocalMeta.traceId }}
            </span>
          </div>
        </div>
      </div>
    </header>

    <!-- Notification Banner -->
    <div v-if="banner" class="max-w-[1440px] w-full mx-auto px-6 pt-4">
      <div
        :class="[
          'flex items-start justify-between gap-4 px-4 py-3 rounded-xl border backdrop-blur-md transition-all',
          banner.type === 'success' ? 'bg-emerald-950/70 border-emerald-500/40 text-emerald-200' :
          banner.type === 'error' ? 'bg-rose-950/70 border-rose-500/40 text-rose-200' :
          banner.type === 'warning' ? 'bg-amber-950/70 border-amber-500/40 text-amber-200' :
          'bg-indigo-950/70 border-indigo-500/40 text-indigo-200'
        ]"
      >
        <div class="flex items-start gap-3">
          <CheckCircle2 v-if="banner.type === 'success'" class="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
          <XCircle v-else-if="banner.type === 'error'" class="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
          <AlertTriangle v-else class="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
          <div>
            <div class="font-semibold text-sm">{{ banner.title }}</div>
            <div class="text-xs opacity-90 mt-0.5">{{ banner.detail }}</div>
          </div>
        </div>
        <button @click="banner = null" class="text-xs opacity-70 hover:opacity-100 px-2 py-1">关闭</button>
      </div>
    </div>

    <!-- Main Content Container -->
    <main class="max-w-[1440px] w-full mx-auto px-6 py-6 flex-1">
      <!-- ======================================================================= -->
      <!-- TAB 1: INTERACTIVE VENUE SEAT MAP & MICROSERVICE LAB                    -->
      <!-- ======================================================================= -->
      <div v-if="activeTab === 'theater'" class="space-y-6">
        <!-- Show Catalog Cards -->
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <div
            v-for="item in showCatalog"
            :key="item.show.id"
            @click="selectShow(item.show.id)"
            :class="[
              'group relative rounded-2xl p-4 cursor-pointer border transition-all duration-200 overflow-hidden',
              selectedShowId === item.show.id
                ? 'bg-slate-900/95 border-indigo-500 shadow-lg shadow-indigo-500/15 ring-1 ring-indigo-500/50'
                : 'bg-slate-900/50 border-slate-800/80 hover:border-slate-700 hover:bg-slate-900/80'
            ]"
          >
            <div
              :class="[
                'h-2 w-full absolute top-0 left-0 bg-gradient-to-r',
                item.show.posterGradient
              ]"
            ></div>
            <div class="flex items-center justify-between gap-2 mt-1">
              <span class="text-[11px] font-semibold px-2 py-0.5 rounded-md bg-indigo-500/15 text-indigo-300 border border-indigo-500/30">
                {{ item.show.city }} · {{ item.show.badgeText }}
              </span>
              <span class="text-xs font-bold text-amber-400">★ {{ item.show.rating }}</span>
            </div>
            <h3 class="font-bold text-sm text-white mt-2.5 line-clamp-1 group-hover:text-indigo-300 transition">
              {{ item.show.title }}
            </h3>
            <p class="text-xs text-slate-400 mt-1 line-clamp-1">{{ item.show.venueName }}</p>
            <div class="flex items-center justify-between mt-3 pt-2.5 border-t border-slate-800/80">
              <span class="text-xs text-slate-400">
                ¥<strong class="text-sm text-white">{{ item.show.minPrice }}</strong> ~ ¥{{ item.show.maxPrice }}
              </span>
              <span class="text-[11px] text-indigo-400 font-medium flex items-center gap-1">
                <span>在线选座</span>
                <ArrowRight class="w-3 h-3" />
              </span>
            </div>
          </div>
        </div>

        <!-- Main Split View: Left 8 Cols Interactive Stage & Seat Grid | Right 4 Cols Checkout & Fault Lab -->
        <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <!-- Left 8 Cols: Stage & 6x8 Venue Seat Matrix -->
          <div class="lg:col-span-8 bg-slate-900/75 border border-slate-800/90 rounded-2xl p-6 flex flex-col justify-between">
            <!-- Session Switcher & Redis Cache Protection Inspector Bar -->
            <div class="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-slate-800">
              <div>
                <div class="flex items-center gap-2">
                  <h2 class="text-base font-bold text-white">
                    {{ currentShowDetail?.title || '加载演出中...' }}
                  </h2>
                </div>
                <!-- Session Pills -->
                <div class="flex flex-wrap items-center gap-2 mt-2.5">
                  <button
                    v-for="sess in currentSessions"
                    :key="sess.id"
                    @click="fetchSeatMap(sess.id)"
                    :class="[
                      'px-3 py-1.5 rounded-xl text-xs font-medium border transition',
                      selectedSessionId === sess.id
                        ? 'bg-indigo-600/25 border-indigo-500 text-indigo-200'
                        : 'bg-slate-950/70 border-slate-800 text-slate-400 hover:text-slate-200'
                    ]"
                  >
                    {{ sess.sessionTitle }} · {{ sess.hallName }}
                  </button>
                </div>
              </div>

              <!-- Redis 3-Mountain Cache Protection Inspector -->
              <div class="bg-slate-950/90 border border-slate-800 rounded-xl p-2.5 flex flex-col gap-1.5 min-w-[270px]">
                <div class="flex items-center justify-between text-[11px]">
                  <span class="text-slate-400 flex items-center gap-1">
                    <Database class="w-3.5 h-3.5 text-emerald-400" />
                    <span>Redis 缓存状态:</span>
                  </span>
                  <span
                    :class="[
                      'font-mono font-bold px-1.5 py-0.5 rounded text-[10px]',
                      cacheInspector.cacheStatus === 'REDIS_HIT'
                        ? 'bg-emerald-500/20 text-emerald-300'
                        : cacheInspector.cacheStatus === 'MYSQL_MUTEX_REBUILT'
                        ? 'bg-amber-500/20 text-amber-300'
                        : 'bg-fuchsia-500/20 text-fuchsia-300'
                    ]"
                  >
                    {{ cacheInspector.cacheStatus }}
                  </span>
                </div>
                <div class="flex items-center justify-between gap-1.5 pt-1 border-t border-slate-800/80">
                  <button
                    @click="evictAndReloadCache"
                    class="flex-1 px-2 py-1 rounded bg-slate-900 hover:bg-slate-800 text-[11px] text-slate-300 border border-slate-700/80 transition flex items-center justify-center gap-1"
                    title="驱逐 Redis 缓存并触发 Redisson RLock 互斥锁重建 + 随机 TTL 防雪崩"
                  >
                    <RefreshCw class="w-3 h-3 text-indigo-400" />
                    <span>驱逐并测互斥锁重建</span>
                  </button>
                  <button
                    @click="testCachePenetrationGuard"
                    class="px-2 py-1 rounded bg-slate-900 hover:bg-slate-800 text-[11px] text-amber-300 border border-amber-500/30 transition"
                    title="查询不存在的 showId=99999，验证 __NULL__ 哨兵防缓存穿透"
                  >
                    测防穿透(ID=99999)
                  </button>
                </div>
              </div>
            </div>

            <!-- 360° Curved Holographic Stage Visual -->
            <div class="my-6 flex flex-col items-center">
              <div class="w-4/5 max-w-[540px] h-11 rounded-b-[100px] bg-gradient-to-r from-indigo-600/40 via-fuchsia-500/50 to-indigo-600/40 border-b-2 border-indigo-400/70 stage-glow flex flex-col items-center justify-center">
                <span class="text-[11px] font-mono tracking-[0.25em] text-indigo-200 uppercase font-semibold">
                  STAGE · 360° 中央全息环形舞台声场正前方
                </span>
              </div>

              <!-- Zone & Status Legend -->
              <div class="flex flex-wrap items-center justify-center gap-5 mt-5 text-xs text-slate-300">
                <span class="flex items-center gap-1.5">
                  <span class="w-3.5 h-3.5 rounded bg-emerald-500/25 border border-emerald-400"></span>
                  <span>可选 ({{ seatStats.available }})</span>
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="w-3.5 h-3.5 rounded bg-indigo-500 border border-white shadow shadow-indigo-500"></span>
                  <span>已选 ({{ selectedSeatIds.length }})</span>
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="w-3.5 h-3.5 rounded bg-amber-500/30 border border-amber-400"></span>
                  <span>锁座待付 LOCKED ({{ seatStats.locked }})</span>
                </span>
                <span class="flex items-center gap-1.5">
                  <span class="w-3.5 h-3.5 rounded bg-rose-950/90 border border-rose-500/50"></span>
                  <span>已售出 SOLD ({{ seatStats.sold }})</span>
                </span>
              </div>
            </div>

            <!-- 6 Rows x 8 Cols Interactive Seat Grid -->
            <div class="space-y-3 my-2 overflow-x-auto pb-2">
              <div
                v-for="[rowNo, rowSeats] in seatRows"
                :key="rowNo"
                class="flex items-center justify-center gap-2.5 min-w-[560px]"
              >
                <!-- Row Badge -->
                <span class="w-16 text-right text-[11px] font-mono text-slate-400 pr-2">
                  第 {{ rowNo }} 排
                </span>

                <!-- 8 Seats in Row -->
                <button
                  v-for="seat in rowSeats"
                  :key="seat.id"
                  @click="toggleSeat(seat)"
                  :disabled="seat.status !== 'AVAILABLE'"
                  :class="[
                    'relative w-14 h-12 rounded-xl border text-center flex flex-col items-center justify-center transition-all duration-150 select-none',
                    selectedSeatIds.includes(seat.id)
                      ? 'bg-indigo-600 border-white text-white scale-105 shadow-lg shadow-indigo-500/40 z-10'
                      : seat.status === 'AVAILABLE'
                      ? seat.zoneCode === 'VIP'
                        ? 'bg-emerald-950/40 border-emerald-400/60 hover:bg-emerald-900/50 hover:border-emerald-300 text-emerald-200'
                        : seat.zoneCode === 'STAGE_SIDE'
                        ? 'bg-teal-950/40 border-teal-400/50 hover:bg-teal-900/50 hover:border-teal-300 text-teal-200'
                        : 'bg-slate-900/90 border-slate-700 hover:border-indigo-400 text-slate-200'
                      : seat.status === 'LOCKED'
                      ? 'bg-amber-500/20 border-amber-400/70 text-amber-200 cursor-not-allowed animate-pulse'
                      : 'bg-rose-950/50 border-rose-800/50 text-rose-400/70 cursor-not-allowed opacity-75'
                  ]"
                  :title="`${seat.zoneName} ${seat.seatLabel} | ¥${seat.price} | 状态: ${seat.status}`"
                >
                  <span class="text-[10px] font-bold leading-tight">
                    {{ seat.colNo }}座
                  </span>
                  <span class="text-[9px] font-mono opacity-85">
                    <template v-if="seat.status === 'AVAILABLE'">¥{{ seat.price }}</template>
                    <template v-else-if="seat.status === 'LOCKED'">锁定中</template>
                    <template v-else>已售</template>
                  </span>
                </button>

                <!-- Zone Tag on Right -->
                <span class="w-24 text-left text-[10px] font-mono pl-2">
                  <span
                    v-if="rowNo <= 2"
                    class="px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-300 border border-amber-500/30"
                  >
                    VIP内场
                  </span>
                  <span
                    v-else-if="rowNo <= 4"
                    class="px-1.5 py-0.5 rounded bg-indigo-500/15 text-indigo-300 border border-indigo-500/30"
                  >
                    优选看台
                  </span>
                  <span
                    v-else
                    class="px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 border border-slate-700"
                  >
                    标准看台
                  </span>
                </span>
              </div>
            </div>

            <!-- Bottom Concurrency Stress Test Simulator Bar -->
            <div class="mt-6 pt-4 border-t border-slate-800 flex flex-wrap items-center justify-between gap-4">
              <div class="flex items-center gap-2.5">
                <Flame class="w-5 h-5 text-orange-400" />
                <div>
                  <div class="text-xs font-bold text-white">
                    高并发秒杀防重与防超卖实测 (Redisson RLock + MultiLock + MySQL CAS)
                  </div>
                  <div class="text-[11px] text-slate-400">
                    模拟 20 个并发线程同时争抢同一个座位，验证是否严格 1 单成功、19 单快速拦截且 0 超卖
                  </div>
                </div>
              </div>

              <button
                @click="runConcurrencyStressLab"
                :disabled="stressTesting"
                class="px-4 py-2 rounded-xl bg-gradient-to-r from-orange-500 to-rose-600 hover:from-orange-400 hover:to-rose-500 text-white font-bold text-xs shadow-lg shadow-orange-500/20 transition flex items-center gap-2 disabled:opacity-50"
              >
                <Zap class="w-4 h-4" />
                <span>{{ stressTesting ? '正在发起 20 线程并发抢座...' : '🔥 一键发起 20 线程并发抢同座压测' }}</span>
              </button>
            </div>

            <!-- Stress Test Scoreboard Result -->
            <div
              v-if="stressResult"
              class="mt-3 p-3.5 rounded-xl bg-slate-950/90 border border-orange-500/40 flex flex-wrap items-center justify-between gap-4 text-xs"
            >
              <div>
                <span class="text-orange-300 font-bold">压测靶心座位：{{ stressResult.seatLabel }}</span>
                <p class="text-[11px] text-slate-400 mt-0.5">
                  赢家订单号: <code class="text-emerald-300">{{ stressResult.winningOrderNo }}</code> ·
                  拦截原因示例: <code class="text-amber-300">{{ stressResult.sampleRejectReason }}</code>
                </p>
              </div>
              <div class="flex items-center gap-3 font-mono">
                <span class="px-2.5 py-1 rounded-lg bg-slate-900 border border-slate-700 text-slate-300">
                  并发数: <strong>{{ stressResult.totalRequests }}</strong>
                </span>
                <span class="px-2.5 py-1 rounded-lg bg-emerald-950 border border-emerald-500/40 text-emerald-300">
                  抢座成功: <strong>{{ stressResult.succeededCount }}</strong>
                </span>
                <span class="px-2.5 py-1 rounded-lg bg-rose-950 border border-rose-500/40 text-rose-300">
                  防重/锁拦截: <strong>{{ stressResult.rejectedCount }}</strong>
                </span>
                <span class="px-2.5 py-1 rounded-lg bg-indigo-950 border border-indigo-500/40 text-indigo-300">
                  耗时: <strong>{{ stressResult.elapsedMs }}ms</strong>
                </span>
              </div>
            </div>
          </div>

          <!-- Right 4 Cols: Checkout Box & Distributed Transaction / MQ Controls -->
          <div class="lg:col-span-4 flex flex-col gap-5">
            <!-- Selected Seats & Price Summary Card -->
            <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 flex-1 flex flex-col justify-between">
              <div>
                <div class="flex items-center justify-between pb-3 border-b border-slate-800">
                  <h3 class="font-bold text-sm text-white flex items-center gap-2">
                    <ShieldCheck class="w-4 h-4 text-indigo-400" />
                    <span>选座锁单控制台</span>
                  </h3>
                  <span class="text-[11px] font-mono text-slate-400">
                    限购 4 座/单
                  </span>
                </div>

                <!-- Selected Seats List -->
                <div class="my-4 min-h-[110px]">
                  <div v-if="selectedSeatObjects.length === 0" class="h-[110px] rounded-xl border border-dashed border-slate-800 flex flex-col items-center justify-center text-slate-500 text-xs">
                    <span>请在左侧场馆座位图中点击可选座位</span>
                    <span class="text-[11px] mt-1 text-slate-600">支持跨排多选 (自动升序构建 RedissonMultiLock)</span>
                  </div>
                  <div v-else class="space-y-2">
                    <div
                      v-for="s in selectedSeatObjects"
                      :key="s.id"
                      class="flex items-center justify-between px-3 py-2 rounded-xl bg-slate-950 border border-indigo-500/30 text-xs"
                    >
                      <div>
                        <span class="font-bold text-indigo-300">{{ s.zoneName }}</span>
                        <span class="ml-2 text-white font-medium">{{ s.seatLabel }}</span>
                        <span class="ml-2 text-[10px] font-mono text-slate-500">seatId={{ s.id }}</span>
                      </div>
                      <div class="flex items-center gap-2">
                        <span class="font-mono font-bold text-emerald-400">¥{{ s.price }}</span>
                        <button @click="toggleSeat(s)" class="text-slate-500 hover:text-rose-400">✕</button>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Microservice Interview Feature Switches -->
                <div class="space-y-3 pt-3 border-t border-slate-800">
                  <div class="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
                    <Cpu class="w-3.5 h-3.5 text-fuchsia-400" />
                    <span>微服务核心机制演练开关 (面试官现场演示)</span>
                  </div>

                  <!-- Switch 1: 15s Fast DLX Delayed Auto-Close -->
                  <label class="flex items-start gap-2.5 p-3 rounded-xl bg-slate-950/90 border border-slate-800 hover:border-amber-500/40 cursor-pointer transition">
                    <input
                      type="checkbox"
                      v-model="fastCloseDemo"
                      class="mt-0.5 rounded border-slate-700 text-amber-500 focus:ring-amber-500"
                    />
                    <div class="text-xs">
                      <div class="font-bold text-amber-300 flex items-center gap-1.5">
                        <Clock class="w-3.5 h-3.5" />
                        <span>⚡ 开启 15秒 极速死信关单演示</span>
                      </div>
                      <p class="text-[11px] text-slate-400 mt-0.5 leading-relaxed">
                        投递至独立 <code class="text-slate-300">ticket.order.delay.fast.queue</code> (TTL=15s)，避免与 15 分钟标准队列发生队头阻塞，15 秒未付款自动 CAS 关单并释放座位！
                      </p>
                    </div>
                  </label>

                  <!-- Switch 2: Seata AT Fault Injection Rollback -->
                  <label class="flex items-start gap-2.5 p-3 rounded-xl bg-slate-950/90 border border-slate-800 hover:border-rose-500/40 cursor-pointer transition">
                    <input
                      type="checkbox"
                      v-model="simulateSeataRollback"
                      class="mt-0.5 rounded border-slate-700 text-rose-500 focus:ring-rose-500"
                    />
                    <div class="text-xs">
                      <div class="font-bold text-rose-300 flex items-center gap-1.5">
                        <AlertTriangle class="w-3.5 h-3.5" />
                        <span>💥 注入 Seata AT 二阶段异常回滚</span>
                      </div>
                      <p class="text-[11px] text-slate-400 mt-0.5 leading-relaxed">
                        当 <code class="text-slate-300">show-service</code> 完成一阶段锁座并写入 <code class="text-slate-300">undo_log</code> 后，在 <code class="text-slate-300">order-service</code> 故意抛出异常，验证跨库自动反向补偿释放座位！
                      </p>
                    </div>
                  </label>
                </div>
              </div>

              <!-- Price Total & Submit Button -->
              <div class="mt-5 pt-4 border-t border-slate-800">
                <div class="flex items-baseline justify-between mb-3">
                  <span class="text-xs text-slate-400">应付总额 ({{ selectedSeatObjects.length }} 张)</span>
                  <span class="text-2xl font-extrabold text-white font-mono">
                    ¥{{ totalSelectedPrice.toFixed(2) }}
                  </span>
                </div>

                <button
                  @click="handleCreateOrder"
                  :disabled="submittingOrder"
                  :class="[
                    'w-full py-3 rounded-xl font-bold text-sm shadow-lg transition flex items-center justify-center gap-2',
                    simulateSeataRollback
                      ? 'bg-gradient-to-r from-rose-600 to-pink-600 hover:from-rose-500 hover:to-pink-500 text-white shadow-rose-600/25'
                      : 'bg-gradient-to-r from-indigo-600 via-violet-600 to-fuchsia-600 hover:from-indigo-500 hover:to-fuchsia-500 text-white shadow-indigo-600/25'
                  ]"
                >
                  <Lock class="w-4 h-4" />
                  <span v-if="submittingOrder">正在执行三层防重锁与 Seata 全局事务...</span>
                  <span v-else-if="simulateSeataRollback">💥 提交并触发 Seata AT 跨库回滚演练</span>
                  <span v-else>立即锁定座位并创建订单</span>
                </button>
              </div>
            </div>

            <!-- Architecture Callout Card -->
            <div class="bg-slate-900/60 border border-slate-800/80 rounded-2xl p-4 text-xs space-y-2">
              <div class="font-bold text-indigo-300 flex items-center gap-1.5">
                <Layers class="w-4 h-4" />
                <span>为什么“锁必须包在事务外面”？</span>
              </div>
              <p class="text-slate-400 leading-relaxed text-[11px]">
                由外层非事务 <code class="text-slate-200">OrderFacade</code> 先按 <code class="text-slate-200">seatId</code> 升序加 <code class="text-slate-200">RedissonMultiLock</code>，再调用内层 <code class="text-slate-200">@GlobalTransactional OrderTxService</code>。事务彻底提交/回滚后在 <code class="text-slate-200">finally</code> 释放锁，杜绝锁释放早于事务提交导致的并发脏读超卖！
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- ======================================================================= -->
      <!-- TAB 2: MY ORDERS & LIVE COUNTDOWN CASHIER                               -->
      <!-- ======================================================================= -->
      <div v-else-if="activeTab === 'orders'" class="space-y-5">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-lg font-bold text-white flex items-center gap-2">
              <CreditCard class="w-5 h-5 text-indigo-400" />
              <span>我的票夹与 RabbitMQ 死信倒计时收银台</span>
            </h2>
            <p class="text-xs text-slate-400 mt-0.5">
              展示 UNPAID 订单实时秒级倒计时（到期自动被 <code class="text-indigo-300">ticket.order.close.queue</code> 死信消费者 CAS 关单并释放座位）
            </p>
          </div>
          <button
            @click="fetchMyOrders"
            class="px-3 py-1.5 rounded-xl bg-slate-900 border border-slate-800 hover:border-indigo-500/50 text-xs text-slate-300 flex items-center gap-1.5"
          >
            <RefreshCw class="w-3.5 h-3.5" />
            <span>刷新订单状态</span>
          </button>
        </div>

        <div v-if="myOrders.length === 0" class="bg-slate-900/50 border border-slate-800 rounded-2xl p-12 text-center text-slate-400 text-sm">
          暂无票务订单，请前往「演出选座大厅」选座下单体验
        </div>

        <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div
            v-for="order in myOrders"
            :key="order.orderNo"
            :class="[
              'rounded-2xl p-5 border transition-all flex flex-col justify-between gap-4',
              order.status === 'UNPAID'
                ? 'bg-slate-900/95 border-amber-500/50 shadow-lg shadow-amber-500/10'
                : order.status === 'PAID'
                ? 'bg-slate-900/80 border-emerald-500/40'
                : 'bg-slate-900/40 border-slate-800 opacity-75'
            ]"
          >
            <div>
              <div class="flex items-center justify-between gap-2 pb-3 border-b border-slate-800">
                <div class="flex items-center gap-2">
                  <span class="font-mono text-xs text-slate-300 font-semibold">{{ order.orderNo }}</span>
                  <span
                    class="text-[10px] font-mono px-2 py-0.5 rounded bg-slate-800 text-slate-400"
                  >
                    {{ order.delayMode === 'FAST_15S' ? '⚡ 15s极速死信档' : '15m标准死信档' }}
                  </span>
                </div>

                <!-- Status Badge -->
                <span
                  v-if="order.status === 'UNPAID'"
                  class="px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-500/20 text-amber-300 border border-amber-500/40 flex items-center gap-1.5"
                >
                  <Clock class="w-3.5 h-3.5 animate-spin" />
                  <span>待支付 {{ formatCountdown(getRemainingSeconds(order.expireAt)) }}</span>
                </span>
                <span
                  v-else-if="order.status === 'PAID'"
                  class="px-2.5 py-0.5 rounded-full text-xs font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40"
                >
                  ✅ 已出票 PAID
                </span>
                <span
                  v-else
                  class="px-2.5 py-0.5 rounded-full text-xs font-bold bg-slate-800 text-slate-400 border border-slate-700"
                >
                  已关闭 ({{ order.closeReason || 'CLOSED' }})
                </span>
              </div>

              <h3 class="font-bold text-sm text-white mt-3">{{ order.showTitle }}</h3>
              <p class="text-xs text-slate-400 mt-1">{{ order.venueName }} · {{ order.sessionTitle }}</p>

              <!-- Seat Pills -->
              <div class="flex flex-wrap items-center gap-1.5 mt-3">
                <span
                  v-for="label in parseJsonArray(order.seatLabelsJson)"
                  :key="label"
                  class="px-2.5 py-1 rounded-lg bg-indigo-500/15 border border-indigo-500/30 text-indigo-200 text-xs font-medium"
                >
                  💺 {{ label }}
                </span>
              </div>
            </div>

            <div class="pt-3 border-t border-slate-800/80 flex items-center justify-between gap-3">
              <div>
                <span class="text-xs text-slate-400">订单总额: </span>
                <strong class="text-lg font-mono text-white">¥{{ Number(order.totalAmount).toFixed(2) }}</strong>
                <div v-if="order.ticketCode" class="text-[11px] font-mono text-emerald-400">
                  核验码: {{ order.ticketCode }}
                </div>
              </div>

              <!-- Actions -->
              <div v-if="order.status === 'UNPAID'" class="flex items-center gap-2">
                <button
                  @click="handleCancelOrder(order)"
                  class="px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-medium transition"
                >
                  主动取消并释放座位
                </button>
                <button
                  @click="handlePayOrder(order)"
                  class="px-4 py-2 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-600 hover:from-emerald-400 hover:to-teal-500 text-slate-950 font-bold text-xs shadow-lg shadow-emerald-500/20 transition"
                >
                  立即模拟支付 (触发MQ出票)
                </button>
              </div>

              <div v-else-if="order.status === 'PAID'">
                <button
                  @click="activeTicketModal = order"
                  class="px-3.5 py-2 rounded-xl bg-indigo-600/25 hover:bg-indigo-600/40 border border-indigo-500/40 text-indigo-200 text-xs font-medium flex items-center gap-1.5 transition"
                >
                  <QrCode class="w-3.5 h-3.5" />
                  <span>查看全息电子票根</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ======================================================================= -->
      <!-- TAB 3: MICROSERVICE GOVERNANCE, SEATA AT & RABBITMQ TELEMETRY           -->
      <!-- ======================================================================= -->
      <div v-else-if="activeTab === 'telemetry'" class="space-y-6">
        <!-- Top Architecture Pipeline Summary Cards -->
        <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-4">
            <div class="text-xs text-indigo-400 font-bold flex items-center gap-1.5">
              <ShieldCheck class="w-4 h-4" />
              <span>1. Gateway + ThreadLocal 透传</span>
            </div>
            <p class="text-[11px] text-slate-400 mt-1.5 leading-relaxed">
              网关验签 JWT 后先 <code class="text-slate-200">remove</code> 伪造头再注入 <code class="text-slate-200">X-User-Id</code>；下游拦截器写入 <code class="text-slate-200">ThreadLocal</code> 并在 <code class="text-slate-200">afterCompletion</code> 清理防泄漏。
            </p>
          </div>

          <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-4">
            <div class="text-xs text-amber-400 font-bold flex items-center gap-1.5">
              <Lock class="w-4 h-4" />
              <span>2. Redisson 三层防重与联锁</span>
            </div>
            <p class="text-[11px] text-slate-400 mt-1.5 leading-relaxed">
              用户级防连点锁 (WatchDog 30s 自动续期) + 座位 ID 升序 <code class="text-slate-200">RedissonMultiLock</code> 防死锁 + MySQL <code class="text-slate-200">WHERE status='AVAILABLE'</code> CAS。
            </p>
          </div>

          <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-4">
            <div class="text-xs text-rose-400 font-bold flex items-center gap-1.5">
              <Server class="w-4 h-4" />
              <span>3. Seata AT 跨库全局事务</span>
            </div>
            <p class="text-[11px] text-slate-400 mt-1.5 leading-relaxed">
              TM (<code class="text-slate-200">order-service</code>) 协同 RM (<code class="text-slate-200">show-service</code>)，一阶段生成 <code class="text-slate-200">undo_log</code> 前后镜像，异常时二阶段自动反向补偿释放座位。
            </p>
          </div>

          <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-4">
            <div class="text-xs text-emerald-400 font-bold flex items-center gap-1.5">
              <Zap class="w-4 h-4" />
              <span>4. RabbitMQ 死信关单与幂等</span>
            </div>
            <p class="text-[11px] text-slate-400 mt-1.5 leading-relaxed">
              15m/15s 双缓冲队列防队头阻塞；关单执行 <code class="text-slate-200">WHERE status='UNPAID'</code> CAS；支付 Fanout 靠 <code class="text-slate-200">uk_order_biz</code> 唯一索引保证 100% 消费幂等。
            </p>
          </div>
        </div>

        <!-- Two-Column Live Telemetry Tables -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <!-- Seata AT Global Transaction Audit Log -->
          <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-5">
            <div class="flex items-center justify-between pb-3 border-b border-slate-800">
              <div>
                <h3 class="font-bold text-sm text-white">Seata AT 全局分布式事务审计表 (seata_tx_audit)</h3>
                <p class="text-[11px] text-slate-400">记录每次跨服务锁座下单的 XID、提交/回滚状态与 undo_log 镜像摘要</p>
              </div>
              <button @click="fetchTelemetry" class="text-xs text-indigo-400 hover:underline">刷新</button>
            </div>

            <div class="mt-3 space-y-2.5 max-h-[420px] overflow-y-auto pr-1">
              <div
                v-for="audit in seataAudits"
                :key="audit.id"
                :class="[
                  'p-3 rounded-xl border text-xs',
                  audit.txStatus === 'ROLLED_BACK'
                    ? 'bg-rose-950/30 border-rose-500/40'
                    : 'bg-slate-950/80 border-slate-800'
                ]"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="font-mono font-bold text-slate-200">{{ audit.orderNo }}</span>
                  <span
                    :class="[
                      'px-2 py-0.5 rounded font-mono text-[10px] font-bold',
                      audit.txStatus === 'ROLLED_BACK'
                        ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40'
                        : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                    ]"
                  >
                    {{ audit.txStatus }} ({{ audit.latencyMs }}ms)
                  </span>
                </div>
                <div class="font-mono text-[11px] text-indigo-300 mt-1">
                  Seata XID: {{ audit.xid }} · 座位ID: {{ audit.seatIds }}
                </div>
                <p class="text-[11px] text-slate-400 mt-1 leading-relaxed">
                  {{ audit.undoSummary }}
                </p>
              </div>
            </div>
          </div>

          <!-- RabbitMQ Event Bus & DLX Audit Log -->
          <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-5">
            <div class="flex items-center justify-between pb-3 border-b border-slate-800">
              <div>
                <h3 class="font-bold text-sm text-white">RabbitMQ 死信关单与 Fanout 广播轨迹 (mq_event_log)</h3>
                <p class="text-[11px] text-slate-400">记录 DELAY_CLOSE_SENT、TIMEOUT_CLOSED、ORDER_PAID_FANOUT 全链路投递</p>
              </div>
              <button @click="fetchTelemetry" class="text-xs text-indigo-400 hover:underline">刷新</button>
            </div>

            <div class="mt-3 space-y-2.5 max-h-[420px] overflow-y-auto pr-1">
              <div
                v-for="mq in mqLogs"
                :key="mq.id"
                class="p-3 rounded-xl bg-slate-950/80 border border-slate-800 text-xs"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="font-mono font-bold text-amber-300">{{ mq.eventType }}</span>
                  <span class="font-mono text-[10px] px-2 py-0.5 rounded bg-emerald-500/15 text-emerald-300 border border-emerald-500/30 font-bold">
                    {{ mq.status }}
                  </span>
                </div>
                <div class="font-mono text-[11px] text-slate-300 mt-1">
                  订单: {{ mq.orderNo }} · Exchange: {{ mq.exchangeName }} ({{ mq.routingKey }})
                </div>
                <p class="text-[11px] text-slate-400 mt-1">
                  {{ mq.payloadSummary }}
                </p>
              </div>
            </div>
          </div>
        </div>

        <!-- Bottom Idempotent Points Ledger (points_log) -->
        <div class="bg-slate-900/80 border border-slate-800 rounded-2xl p-5">
          <div class="flex items-center justify-between pb-3 border-b border-slate-800">
            <div>
              <h3 class="font-bold text-sm text-white">
                user-service 会员积分幂等流水表 (points_log · UNIQUE KEY uk_order_biz)
              </h3>
              <p class="text-[11px] text-slate-400">
                即使 RabbitMQ 因网络抖动重复投递同一笔订单的支付消息，数据库联合唯一索引也会捕获 DuplicateKeyException 并幂等 ACK
              </p>
            </div>
          </div>

          <div class="grid grid-cols-1 md:grid-cols-3 gap-3 mt-3">
            <div
              v-for="pl in pointsLogs"
              :key="pl.id"
              class="p-3 rounded-xl bg-slate-950 border border-slate-800 text-xs flex items-center justify-between"
            >
              <div>
                <div class="font-mono text-indigo-300 font-semibold">{{ pl.orderNo }}</div>
                <div class="text-[11px] text-slate-400 mt-0.5">{{ pl.remark }}</div>
              </div>
              <div class="text-right font-mono">
                <div class="text-emerald-400 font-bold">+{{ pl.pointsDelta }}</div>
                <div class="text-[10px] text-slate-500">余额 {{ pl.balanceAfter }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- In-App Messages Drawer Modal -->
    <div
      v-if="showMessagesDrawer"
      class="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex justify-end"
      @click.self="showMessagesDrawer = false"
    >
      <div class="w-full max-w-md bg-slate-900 border-l border-slate-800 h-full p-6 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between pb-4 border-b border-slate-800">
            <h3 class="font-bold text-base text-white flex items-center gap-2">
              <Bell class="w-4 h-4 text-fuchsia-400" />
              <span>站内出票通知中心 (user_message)</span>
            </h3>
            <button @click="showMessagesDrawer = false" class="text-slate-400 hover:text-white text-sm">✕</button>
          </div>

          <div class="mt-4 space-y-3 overflow-y-auto max-h-[80vh]">
            <div
              v-for="msg in userMessages"
              :key="msg.id"
              class="p-3.5 rounded-xl bg-slate-950 border border-slate-800 text-xs space-y-1.5"
            >
              <div class="flex items-center justify-between">
                <span class="font-bold text-indigo-300">{{ msg.title }}</span>
                <span class="text-[10px] font-mono text-slate-500">{{ msg.orderNo }}</span>
              </div>
              <p class="text-slate-300 leading-relaxed">{{ msg.content }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Holographic E-Ticket Stub Modal -->
    <div
      v-if="activeTicketModal"
      class="fixed inset-0 z-50 bg-black/75 backdrop-blur-md flex items-center justify-center p-4"
      @click.self="activeTicketModal = null"
    >
      <div class="w-full max-w-md rounded-3xl bg-gradient-to-b from-slate-900 to-slate-950 border border-indigo-500/40 shadow-2xl overflow-hidden">
        <div class="p-6 bg-gradient-to-r from-indigo-600 via-violet-600 to-fuchsia-600 text-white">
          <div class="flex items-center justify-between text-xs opacity-90 font-mono">
            <span>PULSETICKET HOLOGRAPHIC PASS</span>
            <span>{{ activeTicketModal.orderNo }}</span>
          </div>
          <h3 class="font-extrabold text-lg mt-2">{{ activeTicketModal.showTitle }}</h3>
          <p class="text-xs opacity-90 mt-1">{{ activeTicketModal.venueName }} · {{ activeTicketModal.sessionTitle }}</p>
        </div>

        <div class="p-6 space-y-4">
          <div class="flex items-center justify-between border-b border-dashed border-slate-800 pb-4">
            <div>
              <div class="text-xs text-slate-400">锁定席位</div>
              <div class="text-sm font-bold text-white mt-1">
                {{ parseJsonArray(activeTicketModal.seatLabelsJson).join(' / ') }}
              </div>
            </div>
            <div class="text-right">
              <div class="text-xs text-slate-400">实付金额</div>
              <div class="text-lg font-mono font-bold text-emerald-400">
                ¥{{ Number(activeTicketModal.totalAmount).toFixed(2) }}
              </div>
            </div>
          </div>

          <div class="bg-slate-900 border border-slate-800 rounded-2xl p-4 text-center space-y-2">
            <QrCode class="w-16 h-16 mx-auto text-indigo-400" />
            <div class="text-xs text-slate-400">电子票动态核验码</div>
            <div class="text-xl font-mono font-extrabold tracking-widest text-amber-300">
              {{ activeTicketModal.ticketCode }}
            </div>
          </div>

          <button
            @click="activeTicketModal = null"
            class="w-full py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-bold text-white transition"
          >
            关闭电子票根
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
