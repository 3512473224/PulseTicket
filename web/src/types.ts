export type Tab = 'theater' | 'orders' | 'telemetry'
export type ZoneCode = 'VIP' | 'STAGE_SIDE' | 'STANDARD'
export type SeatStatus = 'AVAILABLE' | 'LOCKED' | 'SOLD'
export type OrderStatus = 'UNPAID' | 'PAID' | 'CLOSED'
export type UserRole = 'USER' | 'ADMIN'

export interface ApiResult<T = any> {
  code: number
  message: string
  data: T
  traceId?: string
}

export interface UserVO {
  id: number
  username: string
  nickname: string
  phone: string
  vipLevel: string
  role?: UserRole
  pointsBalance: number
  totalOrders: number
}

export interface ShowInfo {
  id: number
  title: string
  subtitle: string
  category: string
  city: string
  venueName: string
  badgeText: string
  rating: number
  minPrice: number
  maxPrice: number
  description: string
}

export interface ShowSession {
  id: number
  showId: number
  sessionTitle: string
  hallName: string
  startTime: string
}

export interface CatalogItem {
  show: ShowInfo
  sessions: ShowSession[]
}

export interface SeatItem {
  id: number
  sessionId: number
  rowNo: number
  colNo: number
  seatLabel: string
  zoneCode: ZoneCode
  zoneName: string
  price: number
  status: SeatStatus
}

export interface TicketOrder {
  id: number
  orderNo: string
  showId: number
  showTitle: string
  venueName: string
  sessionTitle: string
  seatLabelsJson: string
  seatCount: number
  totalAmount: number
  status: OrderStatus
  closeReason?: string | null
  delaySeconds?: number
  ticketCode?: string | null
  seataXid?: string | null
  expireAt: string
  createdAt: string
}

export interface VerifiedTicket {
  valid: boolean
  status: OrderStatus
  ticketCode: string
  orderNo: string
  showId: number
  showTitle: string
  venueName: string
  sessionTitle: string
  seatLabelsJson: string
  seatCount: number
  totalAmount: number
  paidAt?: string | null
  verifiedAt: string
}

export interface SeataAudit {
  id: number
  xid: string
  orderNo: string
  seatIds: string
  txStatus: 'COMMITTED' | 'ROLLED_BACK' | string
  undoSummary: string
  latencyMs: number
  createdAt: string
}

export interface MqLog {
  id: number
  messageId: string
  exchangeName: string
  routingKey: string
  orderNo: string
  eventType: string
  payloadSummary: string
  status: string
  createdAt: string
}

export interface PointsLog {
  id: number
  orderNo: string
  pointsDelta: number
  balanceAfter: number
  remark: string
  createdAt: string
}

export interface UserMessage {
  id: number
  orderNo: string
  title: string
  content: string
  isRead: number
  createdAt: string
}

export interface Toast {
  id: number
  type: 'success' | 'error' | 'info'
  title: string
  detail?: string
}
