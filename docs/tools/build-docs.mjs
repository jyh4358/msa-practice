// ShopSaga 문서 → 완전 오프라인 정적 HTML 사이트 생성기
//
// 사용법:  cd docs/tools && npm install && npm run build
// 출력:    docs/site/*.html  (index.html 을 브라우저에서 더블클릭)
//
// 특징
//  - 각 .md 를 독립 실행형 .html 로 변환 (CSS·문법 하이라이트 전부 파일에 내장 → 서버/인터넷 불필요)
//  - 문서끼리의 .md 상대 링크를 .html 로 자동 변환 (상호 이동 유지)
//  - 좌측 사이드바(전 문서 네비) + 우측 목차(현재 문서 h2/h3) + 코드 문법 하이라이트
//  - Phase 가 추가되면 아래 DOCS 배열에 한 줄 추가하고 다시 실행하면 됨

import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { Marked } from 'marked';
import { markedHighlight } from 'marked-highlight';
import { gfmHeadingId } from 'marked-gfm-heading-id';
import hljs from 'highlight.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '..', '..');       // 저장소 루트
const DOCS_DIR = resolve(ROOT, 'docs');
const SITE_DIR = resolve(DOCS_DIR, 'site');
const HLJS_STYLES = resolve(__dirname, 'node_modules', 'highlight.js', 'styles');

// ── 문서 매니페스트 (사이드바 순서 = 이 순서) ──────────────────────────────
// file: 저장소 루트 기준 상대 경로 · out: docs/site 안의 출력 파일명
const DOCS = [
  { key: 'index',                out: 'index.html', title: '홈 · 개요',              group: '시작',        file: null,
    desc: 'ShopSaga 학습 문서 모음의 시작점' },
  { key: 'README',               out: 'README.html', title: 'README (프로젝트 개요)', group: '시작',        file: 'README.md',
    desc: '현재 상태·서비스 구성·실행/검증 방법' },
  { key: 'MSA-LEARNING-PLAN',    out: 'MSA-LEARNING-PLAN.html', title: '전체 로드맵 (Phase 0~19)', group: '시작', file: 'MSA-LEARNING-PLAN.md',
    desc: 'Phase 0~19 전체 학습 로드맵' },

  { key: 'HEXAGONAL',            out: 'HEXAGONAL.html', title: '헥사고날 아키텍처 컨벤션', group: '공통 개념', file: 'docs/HEXAGONAL.md',
    desc: '포트/어댑터·의존성 방향·QueryDSL 규약' },
  { key: 'SETUP',                out: 'SETUP.html', title: '설치·실행 (트러블슈팅)',    group: '공통 개념', file: 'docs/SETUP.md',
    desc: 'Colima·JDK·Gradle·자주 겪는 문제' },

  { key: 'PHASE-0-SCAFFOLD',     out: 'PHASE-0-SCAFFOLD.html', title: 'Phase 0 · 스캐폴드',       group: 'Phase 단계별', file: 'docs/PHASE-0-SCAFFOLD.md',
    desc: '모노레포·버전 카탈로그·Flyway·동작 증명' },
  { key: 'PHASE-1-MONOLITH',     out: 'PHASE-1-MONOLITH.html', title: 'Phase 1 · 모놀리스(ACID)', group: 'Phase 단계별', file: 'docs/PHASE-1-MONOLITH.md',
    desc: '단일 트랜잭션·비관적 락·QueryDSL·헥사고날' },
  { key: 'PHASE-2-SPLIT-PAYMENT', out: 'PHASE-2-SPLIT-PAYMENT.html', title: 'Phase 2 · 결제 분리', group: 'Phase 단계별', file: 'docs/PHASE-2-SPLIT-PAYMENT.md',
    desc: 'payment 분리 + 원격 REST (단일 트랜잭션 소멸)' },
  { key: 'PHASE-3-GATEWAY',      out: 'PHASE-3-GATEWAY.html', title: 'Phase 3 · API 게이트웨이',  group: 'Phase 단계별', file: 'docs/PHASE-3-GATEWAY.md',
    desc: 'Spring Cloud Gateway 단일 진입점' },
  { key: 'SERVICE-DISCOVERY',    out: 'SERVICE-DISCOVERY.html', title: 'Phase 4 · 서비스 디스커버리', group: 'Phase 단계별', file: 'docs/SERVICE-DISCOVERY.md',
    desc: 'Eureka 등록/조회·클라이언트 사이드 LB' },
  { key: 'SECURITY',             out: 'SECURITY.html', title: 'Phase 5 · 보안(JWT)',        group: 'Phase 단계별', file: 'docs/SECURITY.md',
    desc: 'RS256 JWT 인증·역할 인가·토큰 전파' },
  { key: 'PHASE-6-CONFIG',       out: 'PHASE-6-CONFIG.html', title: 'Phase 6 · 중앙 설정',      group: 'Phase 단계별', file: 'docs/PHASE-6-CONFIG.md',
    desc: 'Spring Cloud Config + 시크릿 암호화' },
  { key: 'PHASE-7-COMPOSE',      out: 'PHASE-7-COMPOSE.html', title: 'Phase 7 · Docker Compose', group: 'Phase 단계별', file: 'docs/PHASE-7-COMPOSE.md',
    desc: '전체 스택 컨테이너 기동·서비스명 DNS' },
  { key: 'PHASE-8-OBSERVABILITY', out: 'PHASE-8-OBSERVABILITY.html', title: 'Phase 8 · 관측성', group: 'Phase 단계별', file: 'docs/PHASE-8-OBSERVABILITY.md',
    desc: '분산 트레이싱·메트릭 (OTLP → otel-lgtm)' },
  { key: 'PHASE-9-ASYNC-KAFKA', out: 'PHASE-9-ASYNC-KAFKA.html', title: 'Phase 9 · 비동기(Kafka)', group: 'Phase 단계별', file: 'docs/PHASE-9-ASYNC-KAFKA.md',
    desc: '재고 분리·OrderPlaced 이벤트·HTTP→Kafka 트레이스·리플레이' },
  { key: 'PHASE-10-OUTBOX', out: 'PHASE-10-OUTBOX.html', title: 'Phase 10 · Outbox+멱등성', group: 'Phase 단계별', file: 'docs/PHASE-10-OUTBOX.md',
    desc: '트랜잭셔널 Outbox·@Scheduled 릴레이·멱등 소비자(effectively-once)' },
  { key: 'PHASE-11-CQRS', out: 'PHASE-11-CQRS.html', title: 'Phase 11 · CQRS 읽기 모델', group: 'Phase 단계별', file: 'docs/PHASE-11-CQRS.md',
    desc: '이벤트 투영·MongoDB 읽기 모델·결정성·리플레이로 재구축' },
  { key: 'PHASE-12-SAGA', out: 'PHASE-12-SAGA.html', title: 'Phase 12 · Saga(보상)', group: 'Phase 단계별', file: 'docs/PHASE-12-SAGA.md',
    desc: '코레오그래피·보상(semantic undo)·주문 상태기계·Saga 한 트레이스' },
  { key: 'PHASE-13-SAGA-ORCHESTRATION', out: 'PHASE-13-SAGA-ORCHESTRATION.html', title: 'Phase 13 · Saga 오케스트레이션', group: 'Phase 단계별', file: 'docs/PHASE-13-SAGA-ORCHESTRATION.md',
    desc: '중앙 조정자·saga_instance·타임아웃 sweep·커맨드 멱등·모드 토글' },

  { key: 'PHASE-14-RESILIENCE', out: 'PHASE-14-RESILIENCE.html', title: 'Phase 14 · 복원력 패턴', group: 'Phase 단계별', file: 'docs/PHASE-14-RESILIENCE.md',
    desc: 'Resilience4j 5종·게이트웨이 회로차단기·DLQ/poison·outbox 격리·고아 결제 보상' },
  { key: 'PHASE-15-CONTRACTS', out: 'PHASE-15-CONTRACTS.html', title: 'Phase 15 · 강화(계약·Bus)', group: 'Phase 단계별', file: 'docs/PHASE-15-CONTRACTS.md',
    desc: 'Spring Cloud Bus 설정 방송·계약 테스트(동기 API+이벤트)·이벤트 스키마 진화' },
  { key: 'PHASE-16-KUBERNETES', out: 'PHASE-16-KUBERNETES.html', title: 'Phase 16 · 로컬 k8s(kind)', group: 'Phase 단계별', file: 'docs/PHASE-16-KUBERNETES.md',
    desc: '16a 클러스터+서비스 이전(probe·ConfigMap/Secret) / 16b Eureka·Config Server 삭제·Ingress·전체 플랫폼' },
  { key: 'PHASE-17-CICD', out: 'PHASE-17-CICD.html', title: 'Phase 17 · CI/CD', group: 'Phase 단계별', file: 'docs/PHASE-17-CICD.md',
    desc: 'GitHub Actions·jar 1회 빌드·네이티브 러너 2개 멀티아치·GHCR·CI 안 kind 스모크 배포' },
  { key: 'PHASE-18-KUSTOMIZE', out: 'PHASE-18-KUSTOMIZE.html', title: 'Phase 18 · 선언적 배포', group: 'Phase 단계별', file: 'docs/PHASE-18-KUSTOMIZE.md',
    desc: 'Kustomize base/overlay·생성기 해시로 설정 변경 자동 롤아웃·CI의 sed 제거·ingress-nginx를 Helm 릴리스로' },
  { key: 'PHASE-19-GITOPS', out: 'PHASE-19-GITOPS.html', title: 'Phase 19 · GitOps', group: 'Phase 단계별', file: 'docs/PHASE-19-GITOPS.md',
    desc: 'Argo CD·CI→Git 승격 루프·selfHeal로 드리프트 복원·prune·비밀을 렌더 밖으로' },

  { key: 'BACKLOG',              out: 'BACKLOG.html', title: 'BACKLOG (남은 주제)',         group: '지도·복습', file: 'docs/BACKLOG.md',
    desc: 'Phase 재번호매김으로 무효화된 "해결 Phase" 약속을 모은 단일 목록' },
  { key: 'AUDIT-2026-08',        out: 'AUDIT-2026-08.html', title: '감사 기록 (2026-08-02)',    group: '지도·복습', file: 'docs/AUDIT-2026-08.md',
    desc: '로드맵/구현/인프라/보안 4개 축 감사 — 발견·수정·이연 기록' },
  { key: 'PHASE-COMMIT-MAP',     out: 'PHASE-COMMIT-MAP.html', title: 'Phase ↔ 커밋 지도',       group: '지도·복습', file: 'docs/PHASE-COMMIT-MAP.md',
    desc: 'git 이력으로 단계별 코드 변화 되짚기' },
  { key: 'REVIEW-PART-A',        out: 'REVIEW-PART-A.html', title: '파트 A 복습 (Phase 0~7)',   group: '지도·복습', file: 'docs/REVIEW-PART-A.md',
    desc: '동기 플랫폼 — 큰 그림·자가진단·셀프 퀴즈·재현 체크리스트' },
  { key: 'REVIEW-PART-B',        out: 'REVIEW-PART-B.html', title: '파트 B 복습 (Phase 8~11)',  group: '지도·복습', file: 'docs/REVIEW-PART-B.md',
    desc: '관측성·비동기·Outbox·CQRS — 이중 쓰기와 멱등을 중심으로' },
  { key: 'REVIEW-PART-C',        out: 'REVIEW-PART-C.html', title: '파트 C 복습 (Phase 12~15)', group: '지도·복습', file: 'docs/REVIEW-PART-C.md',
    desc: 'Saga 두 방식 비교·보상·복원력·계약 테스트' },
  { key: 'REVIEW-PART-D',        out: 'REVIEW-PART-D.html', title: '파트 D 복습 (Phase 16~19)', group: '지도·복습', file: 'docs/REVIEW-PART-D.md',
    desc: 'k8s·CI/CD·Kustomize·GitOps — 명령형에서 선언형으로' },
];

const GROUP_ORDER = ['시작', '공통 개념', 'Phase 단계별', '지도·복습'];

// 소스가 있는(=실제 변환 대상) 문서만, out 파일명 → 존재 여부 집합
const OUT_SET = new Set(DOCS.map(d => d.out));

// ── marked 설정 ────────────────────────────────────────────────────────────
function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const marked = new Marked(
  { gfm: true },
  gfmHeadingId(),
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code, lang) {
      // 언어가 명시되고 hljs가 아는 언어일 때만 하이라이트.
      // 언어 없는 코드펜스(ASCII 다이어그램 등)는 색칠하지 않고 이스케이프만 → 깨짐 방지.
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(code, { language: lang }).value;
      }
      return escapeHtml(code);
    },
  }),
);

// 문서끼리의 .md 링크 → .html 로 변환 (경로 접두사 제거, #앵커 보존)
// 외부(http) 링크는 새 탭으로 열리게 target/rel 을 붙인다 — 학습 흐름이 끊기지 않게.
function rewriteMdLinks(html) {
  return html.replace(/<a href="([^"]+)"/g, (whole, href) => {
    const hashIdx = href.indexOf('#');
    const path = hashIdx === -1 ? href : href.slice(0, hashIdx);
    const anchor = hashIdx === -1 ? '' : href.slice(hashIdx);
    if (/^[a-z]+:\/\//i.test(path) || path.startsWith('mailto:')) {
      return `<a class="ext" target="_blank" rel="noopener" href="${href}"`;
    }
    if (!/\.md$/i.test(path)) return whole;
    const base = path.split('/').pop().replace(/\.md$/i, '.html');
    return `<a href="${base}${anchor}"`;
  });
}

// 렌더된 HTML에서 h2/h3 를 뽑아 우측 목차 생성
function buildToc(html) {
  const re = /<h([23]) id="([^"]+)"[^>]*>([\s\S]*?)<\/h\1>/g;
  const items = [];
  let m;
  while ((m = re.exec(html)) !== null) {
    const level = Number(m[1]);
    const id = m[2];
    const text = m[3].replace(/<[^>]+>/g, '').trim(); // 인라인 태그 제거
    items.push({ level, id, text });
  }
  if (items.length === 0) return '';
  const lis = items
    .map(it => `<li class="toc-h${it.level}"><a href="#${it.id}">${it.text}</a></li>`)
    .join('\n');
  return `<nav class="toc"><div class="toc-title">이 문서 목차</div><ul>${lis}</ul></nav>`;
}

// 좌측 사이드바 (그룹별). currentKey 문서에 active 표시. 상단에 빠른 필터.
function buildSidebar(currentKey) {
  let out = '<div class="brand"><a href="index.html">📚 ShopSaga 문서</a><div class="brand-sub">MSA 핸즈온 · Phase 0~19</div></div>';
  out += '<input class="nav-filter" type="search" placeholder="문서 필터… (예: saga, 재고)" aria-label="문서 필터">';
  for (const group of GROUP_ORDER) {
    const inGroup = DOCS.filter(d => d.group === group);
    if (inGroup.length === 0) continue;
    out += `<div class="nav-group"><div class="nav-group-title">${group}</div><ul>`;
    for (const d of inGroup) {
      const active = d.key === currentKey ? ' class="active"' : '';
      // data-filter: 제목+설명을 소문자로 — 필터 입력과 매칭
      const hay = escapeHtml(`${d.title} ${d.desc || ''}`.toLowerCase());
      out += `<li${active} data-filter="${hay}"><a href="${d.out}">${d.title}</a></li>`;
    }
    out += '</ul></div>';
  }
  return out;
}

// ── 페이지 템플릿 ────────────────────────────────────────────────────────────
// prev/next: 읽기 순서(=DOCS 배열 순서)상의 이전/다음 문서 — 순차 학습용 하단 내비.
function pageHtml({ title, sidebar, body, toc, prev, next }) {
  const pageNav = (prev || next) ? `
  <nav class="page-nav" aria-label="이전/다음 문서">
    ${prev ? `<a class="page-nav-item prev" href="${prev.out}"><span class="page-nav-label">← 이전</span><span class="page-nav-title">${prev.title}</span></a>` : '<span class="page-nav-spacer"></span>'}
    ${next ? `<a class="page-nav-item next" href="${next.out}"><span class="page-nav-label">다음 →</span><span class="page-nav-title">${next.title}</span></a>` : '<span class="page-nav-spacer"></span>'}
  </nav>` : '';
  return `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · ShopSaga 문서</title>
<script>
// 테마 초기화 — 렌더 전에 실행해 깜빡임(FOUC) 방지. 저장값 없으면 시스템 설정 따름.
(function(){try{var t=localStorage.getItem('shopsaga-theme');
if(t==='dark')document.documentElement.classList.add('theme-dark');
else if(t==='light')document.documentElement.classList.add('theme-light');}catch(e){}})();
</script>
<style>
${BASE_CSS}
${HLJS_LIGHT_CSS}
/* 다크 하이라이트 — ① 수동 토글(html.theme-dark) ② 시스템 다크 + 수동 라이트 아님 */
${scopeCss(HLJS_DARK_CSS, 'html.theme-dark')}
@media (prefers-color-scheme: dark) {
${scopeCss(HLJS_DARK_CSS, 'html:not(.theme-light)')}
}
</style>
</head>
<body>
<div class="read-progress" aria-hidden="true"></div>
<button class="nav-toggle" aria-label="메뉴" onclick="document.body.classList.toggle('nav-open')">☰</button>
<button class="theme-toggle" aria-label="라이트/다크 전환" title="라이트/다크 전환">🌓</button>
<div class="nav-backdrop" onclick="document.body.classList.remove('nav-open')"></div>
<aside class="sidebar">${sidebar}</aside>
<main class="content">
  <article class="markdown-body">
${body}
  </article>
${pageNav}
  <footer class="site-footer">
    <hr>
    <p>ShopSaga · 이 페이지는 <code>docs/tools/build-docs.mjs</code> 로 <code>docs/*.md</code> 에서 자동 생성됩니다.
    단축키: <kbd>←</kbd>/<kbd>→</kbd> 이전·다음 문서</p>
  </footer>
</main>
${toc ? `<aside class="toc-wrap">${toc}</aside>` : ''}
<button class="back-to-top" aria-label="맨 위로" title="맨 위로">↑</button>
<script>
${SITE_JS}
</script>
</body>
</html>
`;
}

// 플랫한 CSS(중첩 @규칙 없는 hljs 테마)의 모든 선택자 앞에 scope 를 붙인다.
// 수동 다크 토글(html.theme-dark)과 시스템 다크(:not(.theme-light))에 같은 테마를 재사용하기 위함.
function scopeCss(css, scope) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, '')            // 주석 제거(파싱 단순화)
    .split('}')
    .map(rule => {
      const idx = rule.indexOf('{');
      if (idx === -1) return '';
      const sels = rule.slice(0, idx).trim();
      const decls = rule.slice(idx + 1);
      if (!sels || sels.startsWith('@')) return rule + '}';  // @규칙은 그대로(방어)
      const scoped = sels.split(',').map(s => `${scope} ${s.trim()}`).join(', ');
      return `${scoped}{${decls}}`;
    })
    .join('\n');
}

// ── 랜딩(index) 본문 ──────────────────────────────────────────────────────────
function buildIndexBody() {
  let body = `<h1 id="shopsaga-문서">📚 ShopSaga — MSA 핸즈온 학습 문서</h1>
<p>Spring Cloud로 마이크로서비스를 <strong>한 단계씩 직접 만들며 트레이드오프를 배우는</strong> 학습 프로젝트의 문서 모음입니다.
왼쪽 사이드바 또는 아래 카드에서 문서를 선택하세요. 각 페이지는 서버 없이 <strong>더블클릭</strong>으로 열립니다.</p>
<blockquote><p><strong>현재 상태: Phase 19 완료</strong> — 6개 런타임 서비스(도메인 3 + gateway + auth + query)가
Kafka Saga·Outbox·CQRS 위에서 동작하고, k8s(kind)에 Kustomize 로 선언 배포되며, CI가 만든 이미지를
<strong>Argo CD 가 Git 을 보고 스스로 배포</strong>합니다. 남은 주제는 <a href="BACKLOG.html">BACKLOG</a> 참고.</p></blockquote>
<p class="index-hint">🧭 <strong>처음이라면</strong>: 사이드바의 "Phase 단계별"을 위에서 아래로 순서대로 읽으세요.
각 문서 하단의 <strong>이전/다음</strong> 버튼(키보드 <kbd>←</kbd>/<kbd>→</kbd>)으로 이어 읽을 수 있고,
복습은 각 문서 끝의 <strong>복습 포인트</strong>와 "지도·복습" 그룹의 파트별 복습 문서를 이용하세요.</p>`;

  for (const group of GROUP_ORDER) {
    const inGroup = DOCS.filter(d => d.group === group && d.key !== 'index');
    if (inGroup.length === 0) continue;
    body += `\n<h2 id="${slug(group)}">${group}</h2>\n<div class="card-grid">`;
    for (const d of inGroup) {
      body += `<a class="card" href="${d.out}"><div class="card-title">${d.title}</div><div class="card-desc">${d.desc || ''}</div></a>`;
    }
    body += '</div>';
  }
  return body;
}

function slug(s) {
  return s.toLowerCase().replace(/[^\w가-힣]+/g, '-').replace(/^-+|-+$/g, '');
}

// ── 빌드 실행 ────────────────────────────────────────────────────────────────
function main() {
  // 출력 폴더 초기화
  if (existsSync(SITE_DIR)) rmSync(SITE_DIR, { recursive: true, force: true });
  mkdirSync(SITE_DIR, { recursive: true });

  let built = 0;
  for (let i = 0; i < DOCS.length; i++) {
    const d = DOCS[i];
    let body, toc;
    if (d.key === 'index') {
      body = buildIndexBody();
      toc = '';
    } else {
      const srcPath = resolve(ROOT, d.file);
      if (!existsSync(srcPath)) {
        console.warn(`⚠️  건너뜀(소스 없음): ${d.file}`);
        continue;
      }
      const md = readFileSync(srcPath, 'utf8');
      let html = marked.parse(md);
      html = rewriteMdLinks(html);
      body = html;
      toc = buildToc(html);
    }
    const page = pageHtml({
      title: d.title,
      sidebar: buildSidebar(d.key),
      body,
      toc,
      // 읽기 순서 = DOCS 배열 순서. index 는 프롤로그라 이전 없음.
      prev: i > 0 ? DOCS[i - 1] : null,
      next: i < DOCS.length - 1 ? DOCS[i + 1] : null,
    });
    writeFileSync(resolve(SITE_DIR, d.out), page, 'utf8');
    built++;
  }
  console.log(`✅ 생성 완료: ${built}개 페이지 → ${SITE_DIR}`);
  console.log(`   브라우저에서 열기: open "${resolve(SITE_DIR, 'index.html')}"`);
}

// hljs 테마 CSS 를 파일에서 읽어 내장 (github: 라이트 / github-dark: 다크)
let HLJS_LIGHT_CSS = '';
let HLJS_DARK_CSS = '';
try {
  HLJS_LIGHT_CSS = readFileSync(resolve(HLJS_STYLES, 'github.css'), 'utf8');
  HLJS_DARK_CSS = readFileSync(resolve(HLJS_STYLES, 'github-dark.css'), 'utf8');
} catch (e) {
  console.warn('⚠️  hljs 테마 CSS 를 찾지 못했습니다. `npm install` 을 먼저 실행하세요.');
}

// ── 사이트 CSS (내장) ─────────────────────────────────────────────────────────
// 다크 팔레트 — ① 수동 토글(html.theme-dark) ② 시스템 다크(수동 라이트 아님) 두 곳에서 재사용
const DARK_VARS = `
  --bg:#0d1117; --fg:#e6edf3; --muted:#8b949e; --border:#30363d;
  --sidebar-bg:#161b22; --accent:#4493f8; --accent-soft:#122436;
  --code-bg:#161b22; --code-fg:#e6edf3; --table-alt:#161b22; --quote-border:#30363d;`;

const BASE_CSS = `
:root{
  --bg:#ffffff; --fg:#1f2328; --muted:#656d76; --border:#d0d7de;
  --sidebar-bg:#f6f8fa; --accent:#0969da; --accent-soft:#ddf4ff;
  --code-bg:#f6f8fa; --code-fg:#1f2328; --table-alt:#f6f8fa; --quote-border:#d0d7de;
  --sidebar-w:280px; --toc-w:240px;
}
html.theme-dark{${DARK_VARS}
}
@media (prefers-color-scheme: dark){
  html:not(.theme-light){${DARK_VARS}
  }
}
*{box-sizing:border-box;}
html{scroll-behavior:smooth;}
body{
  margin:0; background:var(--bg); color:var(--fg);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Apple SD Gothic Neo","Malgun Gothic","Noto Sans KR",Roboto,Helvetica,Arial,sans-serif;
  font-size:16px; line-height:1.7;
  -webkit-font-smoothing:antialiased;
}
a{color:var(--accent); text-decoration:none;}
a:hover{text-decoration:underline;}

/* 레이아웃: 사이드바 | 본문 | 목차 */
.sidebar{
  position:fixed; top:0; left:0; width:var(--sidebar-w); height:100vh; overflow-y:auto;
  background:var(--sidebar-bg); border-right:1px solid var(--border); padding:20px 14px 40px;
}
.content{
  margin-left:var(--sidebar-w); margin-right:var(--toc-w);
  padding:44px 48px 80px; max-width:900px;
}
.toc-wrap{
  position:fixed; top:0; right:0; width:var(--toc-w); height:100vh; overflow-y:auto;
  padding:44px 18px 40px; border-left:1px solid var(--border);
}
@media (max-width:1200px){
  .toc-wrap{display:none;}
  .content{margin-right:0;}
}
@media (max-width:860px){
  .content{margin-left:0; padding:64px 20px 60px;}
  .sidebar{transform:translateX(-100%); transition:transform .2s ease; z-index:30; box-shadow:0 0 24px rgba(0,0,0,.2);}
  body.nav-open .sidebar{transform:translateX(0);}
  body.nav-open .nav-backdrop{display:block;}
  .nav-toggle{display:flex;}
}

/* 사이드바 내부 */
.brand a{font-weight:700; font-size:16px; color:var(--fg);}
.brand-sub{font-size:12px; color:var(--muted); margin-top:2px;}
.nav-group{margin-top:20px;}
.nav-group-title{font-size:12px; font-weight:700; color:var(--muted); text-transform:none; letter-spacing:.02em; padding:0 8px 6px;}
.sidebar ul{list-style:none; margin:0; padding:0;}
.sidebar li a{display:block; padding:6px 10px; border-radius:6px; color:var(--fg); font-size:14px;}
.sidebar li a:hover{background:var(--accent-soft); text-decoration:none;}
.sidebar li.active a{background:var(--accent); color:#fff; font-weight:600;}

/* 목차 */
.toc-title{font-size:12px; font-weight:700; color:var(--muted); margin-bottom:8px;}
.toc ul{list-style:none; margin:0; padding:0;}
.toc li{margin:0;}
.toc li a{display:block; padding:3px 8px; font-size:13px; color:var(--muted); border-left:2px solid transparent; line-height:1.4;}
.toc li a:hover{color:var(--accent); text-decoration:none;}
.toc-h3 a{padding-left:20px; font-size:12.5px;}

/* 모바일 토글 */
.nav-toggle{display:none; position:fixed; top:12px; left:12px; z-index:40;
  width:40px; height:40px; align-items:center; justify-content:center;
  background:var(--sidebar-bg); border:1px solid var(--border); border-radius:8px;
  font-size:18px; cursor:pointer; color:var(--fg);}
.nav-backdrop{display:none; position:fixed; inset:0; background:rgba(0,0,0,.4); z-index:20;}

/* 본문 타이포 */
.markdown-body h1,.markdown-body h2,.markdown-body h3,.markdown-body h4{
  line-height:1.3; margin:1.6em 0 .6em; font-weight:700;}
.markdown-body h1{font-size:2em; margin-top:0; padding-bottom:.3em; border-bottom:1px solid var(--border);}
.markdown-body h2{font-size:1.5em; padding-bottom:.3em; border-bottom:1px solid var(--border);}
.markdown-body h3{font-size:1.25em;}
.markdown-body h4{font-size:1.05em;}
.markdown-body p{margin:0 0 1em;}
.markdown-body ul,.markdown-body ol{margin:0 0 1em; padding-left:1.7em;}
.markdown-body li{margin:.2em 0;}
.markdown-body blockquote{
  margin:0 0 1em; padding:.4em 1em; color:var(--muted);
  border-left:4px solid var(--quote-border); background:var(--table-alt);}
.markdown-body blockquote p{margin:.4em 0;}
.markdown-body hr{border:0; border-top:1px solid var(--border); margin:2em 0;}
.markdown-body img{max-width:100%;}

/* 표 */
.markdown-body table{border-collapse:collapse; width:100%; margin:0 0 1.2em; display:block; overflow-x:auto;}
.markdown-body th,.markdown-body td{border:1px solid var(--border); padding:7px 12px; text-align:left;}
.markdown-body th{background:var(--table-alt); font-weight:700;}
.markdown-body tr:nth-child(2n) td{background:var(--table-alt);}

/* 코드 */
.markdown-body code{
  font-family:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,"D2Coding","D2Coding ligature",monospace;
  font-size:.88em;}
.markdown-body :not(pre)>code{
  background:var(--code-bg); padding:.15em .4em; border-radius:6px; border:1px solid var(--border);}
.markdown-body pre{
  background:var(--code-bg); padding:14px 16px; border-radius:8px; overflow-x:auto;
  border:1px solid var(--border); margin:0 0 1.2em; line-height:1.55;}
.markdown-body pre code{background:none; padding:0; border:0; font-size:13.5px; white-space:pre;}

/* 상세(details) */
.markdown-body details{border:1px solid var(--border); border-radius:8px; padding:.6em 1em; margin:0 0 1em; background:var(--table-alt);}
.markdown-body summary{cursor:pointer; font-weight:600;}

/* 랜딩 카드 */
.card-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:14px; margin:0 0 1.6em;}
.card{display:block; border:1px solid var(--border); border-radius:10px; padding:16px; background:var(--sidebar-bg);
  transition:border-color .15s ease, transform .15s ease;}
.card:hover{border-color:var(--accent); text-decoration:none; transform:translateY(-2px);}
.card-title{font-weight:700; color:var(--fg); margin-bottom:6px;}
.card-desc{font-size:13.5px; color:var(--muted); line-height:1.5;}

/* 푸터 */
.site-footer{margin-top:40px; color:var(--muted); font-size:13px;}
.site-footer hr{border:0; border-top:1px solid var(--border); margin:0 0 12px;}
kbd{border:1px solid var(--border); border-bottom-width:2px; border-radius:4px; padding:0 5px;
  font-size:.85em; background:var(--table-alt); font-family:inherit;}

/* 읽기 진행 바 */
.read-progress{position:fixed; top:0; left:0; height:3px; width:0; z-index:60;
  background:var(--accent); transition:width .1s linear;}

/* 테마 토글 */
.theme-toggle{position:fixed; top:12px; right:12px; z-index:40;
  width:40px; height:40px; display:flex; align-items:center; justify-content:center;
  background:var(--sidebar-bg); border:1px solid var(--border); border-radius:8px;
  font-size:17px; cursor:pointer; color:var(--fg);}
.theme-toggle:hover{border-color:var(--accent);}
@media (min-width:1201px){ .theme-toggle{right:calc(var(--toc-w) - 52px);} }

/* 사이드바 필터 */
.nav-filter{width:100%; margin:14px 0 2px; padding:7px 10px; font-size:13px;
  border:1px solid var(--border); border-radius:8px; background:var(--bg); color:var(--fg);}
.nav-filter:focus{outline:none; border-color:var(--accent);}
.sidebar li.filter-hide, .nav-group.filter-hide{display:none;}

/* 이전/다음 문서 내비 */
.page-nav{display:flex; gap:14px; margin:36px 0 0;}
.page-nav-item{flex:1; display:block; border:1px solid var(--border); border-radius:10px;
  padding:12px 16px; background:var(--sidebar-bg);}
.page-nav-item:hover{border-color:var(--accent); text-decoration:none;}
.page-nav-item.next{text-align:right;}
.page-nav-spacer{flex:1;}
.page-nav-label{display:block; font-size:12px; color:var(--muted); margin-bottom:3px;}
.page-nav-title{display:block; font-weight:600; color:var(--fg); font-size:14.5px; line-height:1.4;}
@media (max-width:640px){ .page-nav{flex-direction:column;} .page-nav-item.next{text-align:left;} .page-nav-spacer{display:none;} }

/* 맨 위로 */
.back-to-top{position:fixed; right:18px; bottom:18px; z-index:40; width:42px; height:42px;
  border-radius:50%; border:1px solid var(--border); background:var(--sidebar-bg); color:var(--fg);
  font-size:18px; cursor:pointer; opacity:0; pointer-events:none; transition:opacity .2s ease;
  box-shadow:0 2px 10px rgba(0,0,0,.12);}
.back-to-top.show{opacity:1; pointer-events:auto;}
.back-to-top:hover{border-color:var(--accent); color:var(--accent);}
@media (min-width:1201px){ .back-to-top{right:calc(var(--toc-w) + 18px);} }

/* 목차 현재 위치(스크롤스파이) */
.toc li a.active{color:var(--accent); border-left-color:var(--accent); font-weight:600;}

/* 코드 복사 버튼 */
.markdown-body pre{position:relative;}
.copy-btn{position:absolute; top:8px; right:8px; padding:3px 10px; font-size:12px;
  border:1px solid var(--border); border-radius:6px; background:var(--bg); color:var(--muted);
  cursor:pointer; opacity:0; transition:opacity .15s ease;}
.markdown-body pre:hover .copy-btn{opacity:1;}
.copy-btn:hover{color:var(--accent); border-color:var(--accent);}
.copy-btn.done{color:#1a7f37; border-color:#1a7f37; opacity:1;}

/* 제목 앵커(hover 시 §) */
.heading-anchor{margin-left:6px; font-size:.75em; color:var(--muted); opacity:0; text-decoration:none;}
h2:hover .heading-anchor, h3:hover .heading-anchor{opacity:1;}
.heading-anchor:hover{color:var(--accent); text-decoration:none;}

/* 외부 링크 표시 */
a.ext::after{content:"↗"; font-size:.75em; margin-left:2px; color:var(--muted);}

/* 랜딩 안내문 */
.index-hint{border:1px dashed var(--border); border-radius:10px; padding:10px 14px;
  background:var(--sidebar-bg); font-size:14.5px;}
`;

// ── 페이지 인터랙션 (바닐라 JS, 오프라인 단일 파일 유지) ─────────────────────
// 주의: 아래 문자열은 페이지에 그대로 내장된다 — 백틱/\${} 사용 금지.
const SITE_JS = `
(function(){
'use strict';
var html = document.documentElement;

// 1) 테마 토글 — 현재 '보이는' 테마 기준으로 반전, localStorage 저장
var themeBtn = document.querySelector('.theme-toggle');
function effectiveDark(){
  if (html.classList.contains('theme-dark')) return true;
  if (html.classList.contains('theme-light')) return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}
if (themeBtn) themeBtn.addEventListener('click', function(){
  var toDark = !effectiveDark();
  html.classList.toggle('theme-dark', toDark);
  html.classList.toggle('theme-light', !toDark);
  try { localStorage.setItem('shopsaga-theme', toDark ? 'dark' : 'light'); } catch(e){}
});

// 2) 읽기 진행 바 + 맨 위로 버튼
var bar = document.querySelector('.read-progress');
var topBtn = document.querySelector('.back-to-top');
function onScroll(){
  var h = document.documentElement;
  var max = h.scrollHeight - h.clientHeight;
  if (bar) bar.style.width = (max > 0 ? (h.scrollTop / max * 100) : 0) + '%';
  if (topBtn) topBtn.classList.toggle('show', h.scrollTop > 600);
  spy();
}
if (topBtn) topBtn.addEventListener('click', function(){ window.scrollTo({top:0, behavior:'smooth'}); });

// 3) 코드 복사 버튼
document.querySelectorAll('.markdown-body pre').forEach(function(pre){
  var code = pre.querySelector('code');
  if (!code) return;
  var btn = document.createElement('button');
  btn.className = 'copy-btn'; btn.type = 'button'; btn.textContent = '복사';
  btn.addEventListener('click', function(){
    var text = code.innerText;
    function done(){ btn.textContent = '복사됨 ✓'; btn.classList.add('done');
      setTimeout(function(){ btn.textContent = '복사'; btn.classList.remove('done'); }, 1600); }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done);
    } else {
      var ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta);
      ta.select(); try { document.execCommand('copy'); done(); } catch(e){} document.body.removeChild(ta);
    }
  });
  pre.appendChild(btn);
});

// 4) 목차 스크롤스파이 — 화면 상단을 지난 마지막 제목을 active 로
var tocLinks = Array.prototype.slice.call(document.querySelectorAll('.toc a'));
var headings = tocLinks.map(function(a){
  return document.getElementById(decodeURIComponent(a.getAttribute('href').slice(1)));
}).filter(Boolean);
function spy(){
  if (!headings.length) return;
  var line = 90; // 상단 기준선(px)
  var current = null;
  for (var i = 0; i < headings.length; i++) {
    if (headings[i].getBoundingClientRect().top <= line) current = i; else break;
  }
  tocLinks.forEach(function(a, i){ a.classList.toggle('active', i === current); });
}

// 5) 사이드바 필터 — 제목/설명에 입력어가 포함된 문서만 남김
var filter = document.querySelector('.nav-filter');
if (filter) filter.addEventListener('input', function(){
  var q = filter.value.trim().toLowerCase();
  document.querySelectorAll('.sidebar .nav-group').forEach(function(g){
    var any = false;
    g.querySelectorAll('li').forEach(function(li){
      var hit = !q || (li.getAttribute('data-filter') || '').indexOf(q) !== -1;
      li.classList.toggle('filter-hide', !hit);
      if (hit) any = true;
    });
    g.classList.toggle('filter-hide', !any);
  });
});

// 6) 사이드바에서 현재 문서가 보이게 스크롤
var activeLi = document.querySelector('.sidebar li.active');
if (activeLi && activeLi.scrollIntoView) activeLi.scrollIntoView({ block: 'center' });

// 7) 키보드 ←/→ 로 이전/다음 문서 (입력 중이면 무시)
document.addEventListener('keydown', function(e){
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  var t = e.target;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  var sel = e.key === 'ArrowLeft' ? '.page-nav-item.prev' : e.key === 'ArrowRight' ? '.page-nav-item.next' : null;
  if (!sel) return;
  var link = document.querySelector(sel);
  if (link) window.location.href = link.getAttribute('href');
});

// 8) h2/h3 hover 앵커(§) — 특정 절 링크 복사용
document.querySelectorAll('.markdown-body h2[id], .markdown-body h3[id]').forEach(function(h){
  var a = document.createElement('a');
  a.className = 'heading-anchor'; a.href = '#' + h.id; a.textContent = '§';
  a.setAttribute('aria-label', '이 절 링크');
  h.appendChild(a);
});

window.addEventListener('scroll', onScroll, { passive: true });
onScroll();
})();
`;

main();
