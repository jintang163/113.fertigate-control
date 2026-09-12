import axios, { type AxiosRequestConfig } from 'axios'
import { message } from 'ant-design-vue'
import type { ApiResponse } from '@/types'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 响应解包 ApiResponse：{ code, msg, data }
http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>
    // 非统一包裹结构（理论上后端均遵循契约）直接返回
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return resp.data
    }
    if (body.code === 0) {
      return body.data
    }
    message.error(body.msg || `请求失败（code=${body.code}）`)
    return Promise.reject(new Error(body.msg || `Business error: ${body.code}`))
  },
  (error) => {
    let errMsg = '网络异常，请检查后端服务（:8080）'
    if (error?.response) {
      const { status, data } = error.response
      errMsg = data?.msg || data?.message || `请求错误 ${status}`
    } else if (error?.code === 'ECONNABORTED') {
      errMsg = '请求超时'
    } else if (error?.message && error.message !== 'Network Error') {
      errMsg = error.message
    }
    // GET 失败（轮询/列表加载）不弹窗，由页面空态/离线徽标呈现；POST/PUT 等变更操作弹窗提示
    const method = String(error?.config?.method || 'get').toLowerCase()
    if (method !== 'get') {
      message.error(errMsg)
    } else {
      console.warn('[http] GET 请求失败（页面将展示空态）：', errMsg)
    }
    return Promise.reject(error)
  }
)

/** GET，返回解包后的 data */
export function get<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.get(url, { params, ...config }) as unknown as Promise<T>
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config) as unknown as Promise<T>
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.put(url, data, config) as unknown as Promise<T>
}

export default http
