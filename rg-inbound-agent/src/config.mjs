import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** config.json 로드 (없으면 config.example.json 안내 후 종료) */
export function loadConfig() {
  const file = path.join(ROOT, 'config.json');
  if (!fs.existsSync(file)) {
    console.error('config.json이 없습니다. config.example.json을 복사해 값을 채워주세요.');
    process.exit(1);
  }
  const config = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const key of ['serverUrl', 'agentToken']) {
    if (!config[key]) {
      console.error(`config.json의 ${key} 값이 비어 있습니다.`);
      process.exit(1);
    }
  }
  config.pollIntervalSec = config.pollIntervalSec ?? 600;
  config.profileDir = path.resolve(ROOT, config.profileDir ?? './wing-profile');
  config.downloadDir = path.resolve(ROOT, config.downloadDir ?? './downloads');
  config.loginWaitMinutes = config.loginWaitMinutes ?? 30;
  // 문서(PDF) 보관용 사내 CDN — 교보 발주 원본과 동일한 refrigerator 서비스
  config.refrigerator = {
    endpoint: 'https://refrigerator.logipasta.com/v1/file',
    bucket: 'withcookie-bucket',
    path: 'rg-inbound-docs',
    ...(config.refrigerator ?? {}),
  };
  fs.mkdirSync(config.downloadDir, { recursive: true });
  return config;
}
