import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 10000 })

// 调度员身份：两名调度员并发处理时，后端用 X-Operator 记录每次决定的实际操作人
let currentOperator = '张伟'
export function setOperator(name) { currentOperator = name }
export function getOperator() { return currentOperator }

http.interceptors.request.use(config => {
  config.headers['X-Operator'] = currentOperator
  return config
})

http.interceptors.response.use(
  response => response.data,
  error => {
    const e = new Error(error.response?.data?.message || error.message || '网络请求失败')
    e.status = error.response?.status
    return Promise.reject(e)
  }
)

export const yardApi = {
  zones: () => http.get('/yard/zones'),
  slots: (zone, bayStart = 1, bayEnd = 99) => http.get('/yard/slots', { params: { zone, bayStart, bayEnd } }),
  matrix: (zone, bay) => http.get(`/yard/matrix/${zone}/${bay}`),
  exact: ({ yardZone, bay, row, tier }) => http.get(`/yard/slots/${yardZone}/${bay}/${row}/${tier}`),
  occupancy: () => http.get('/yard/occupancy'),
  bayStats: (zone, bay) => http.get('/yard/bay-stats', { params: { zone, bay } }),
  tierStats: tier => http.get('/yard/tier-stats', { params: { tier } }),
  place: data => http.post('/yard/place', data),
  relocate: data => http.post('/yard/relocate', data),
  pickup: containerNo => http.post(`/yard/pickup/${containerNo}`)
}

export const berthApi = {
  shoreline: () => http.get('/berths/shoreline'),
  schedules: berthId => http.get(`/berths/${berthId}/schedules`),
  assignCranes: (scheduleId, craneCodes) => http.post(`/berths/schedules/${scheduleId}/assign-cranes`, { craneCodes }),
  setStatus: (berthId, status) => http.put(`/berths/${berthId}/status`, null, { params: { status } }),
  depart: scheduleId => http.post(`/berths/schedules/${scheduleId}/depart`)
}

export const craneApi = {
  efficiency: () => http.get('/cranes/efficiency'),
  shifts: craneId => http.get(`/cranes/${craneId}/shifts`),
  registerShift: (craneId, data) => http.post(`/cranes/${craneId}/shifts`, data),
  setStatus: (craneId, status) => http.put(`/cranes/${craneId}/status`, null, { params: { status } })
}

export const containerApi = {
  list: () => http.get('/containers'),
  companySummary: () => http.get('/containers/company-summary'),
  lifecycle: containerNo => http.get(`/containers/${containerNo}/lifecycle`)
}

export const operationApi = {
  gates: () => http.get('/operations/gates'),
  instructions: () => http.get('/operations/instructions'),
  createInstruction: data => http.post('/operations/instructions', data),
  complete: id => http.post(`/operations/instructions/${id}/complete`)
}

export const takeoverApi = {
  sessions: () => http.get('/takeover/sessions'),
  detail: sessionId => http.get(`/takeover/sessions/${sessionId}`),
  reportFault: (craneId, faultReason) => http.post(`/takeover/cranes/${craneId}/fault`, { faultReason }),
  pause: (sessionId, itemIds) => http.post(`/takeover/sessions/${sessionId}/pause`, { itemIds }),
  confirmStage: (sessionId, itemId, stage) => http.post(`/takeover/sessions/${sessionId}/items/${itemId}/confirm-stage`, { stage }),
  handover: (sessionId, itemIds, takeoverCraneCode, mode) =>
    http.post(`/takeover/sessions/${sessionId}/handover`, { itemIds, takeoverCraneCode, mode }),
  resume: (sessionId, itemIds) => http.post(`/takeover/sessions/${sessionId}/resume`, { itemIds }),
  craneRecovered: sessionId => http.post(`/takeover/sessions/${sessionId}/crane-recovered`),
  close: (sessionId, note) => http.post(`/takeover/sessions/${sessionId}/close`, { note })
}
