import fs from 'fs';

/** crawling 서버 에이전트 API 클라이언트 */
export function createApi(config) {
  const base = config.serverUrl.replace(/\/$/, '') + '/internal/coupang/rg-inbound/agent';
  const headers = { 'X-Agent-Token': config.agentToken };

  async function request(method, url, body) {
    const options = { method, headers: { ...headers } };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    const response = await fetch(url, options);
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* 비JSON 응답 */ }
    if (!response.ok) {
      const message = json?.message || json?.error || text || response.status;
      throw new Error(`API ${method} ${url} 실패: ${message}`);
    }
    return json;
  }

  return {
    /** 실행할 작업 목록 */
    async getJobs() {
      const data = await request('GET', `${base}/jobs`);
      return data?.jobs ?? [];
    },

    async startPlan(planId) {
      return request('POST', `${base}/plans/${planId}/start`);
    },

    async submitResult(planId, result) {
      return request('POST', `${base}/plans/${planId}/submit-result`, result);
    },

    async invoiceResult(planId, result) {
      return request('POST', `${base}/plans/${planId}/invoice-result`, result);
    },

    async syncAddresses(addresses) {
      return request('POST', `${base}/addresses`, { addresses });
    },

    /** 상태 알림 (서버가 슬랙으로 릴레이). 실패해도 흐름을 막지 않는다. */
    async notify(message) {
      try {
        await request('POST', `${base}/notify`, { message });
      } catch (e) {
        console.error('알림 전송 실패:', e.message);
      }
    },

    /** 회수 문서(PDF) 업로드. type: barcode | attachment */
    async uploadDocument(planId, type, filePath) {
      const form = new FormData();
      const buffer = await fs.promises.readFile(filePath);
      form.append('file', new Blob([buffer], { type: 'application/pdf' }), `plan${planId}-${type}.pdf`);
      const response = await fetch(`${base}/plans/${planId}/documents/${type}`, {
        method: 'POST',
        headers,
        body: form,
      });
      if (!response.ok) {
        throw new Error(`문서 업로드 실패 (${type}): HTTP ${response.status}`);
      }
    },
  };
}
