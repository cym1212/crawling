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

    /** 문서 CDN URL 후속 보고 (제출 시 함께 보고하지 못한 경우). type: barcode | attachment */
    async reportDocument(planId, type, url) {
      return request('POST', `${base}/plans/${planId}/documents/${type}`, { url });
    },
  };
}

/**
 * 사내 CDN(refrigerator)에 파일 업로드 → CDN URL 반환 (교보 발주 원본과 동일 방식).
 * multipart: bucket / path / file — 인증 없음, 응답 JSON의 .file 이 CDN URL.
 */
export async function uploadToRefrigerator(config, filePath, filename) {
  const cdn = config.refrigerator;
  const form = new FormData();
  form.append('bucket', cdn.bucket);
  form.append('path', cdn.path);
  const buffer = await fs.promises.readFile(filePath);
  form.append('file', new Blob([buffer], { type: 'application/pdf' }), filename);

  const response = await fetch(cdn.endpoint, { method: 'POST', body: form });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`CDN 업로드 실패 (HTTP ${response.status}): ${text.slice(0, 200)}`);
  }
  let json = null;
  try { json = JSON.parse(text); } catch { /* 아래에서 처리 */ }
  if (!json || !json.file) {
    throw new Error(`CDN 응답에 file(URL) 없음: ${text.slice(0, 200)}`);
  }
  return json.file;
}
