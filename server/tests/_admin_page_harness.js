// Dùng bởi tests/test_admin_page_security.py: chạy toàn bộ <script> của admin.html trong sandbox `vm` với DOM giả
// tối thiểu, gọi renderUserList() và safeAvatarUrl() rồi in kết quả để Python kiểm tra.
// stdin: JSON {html: <nội dung admin.html>, users: [...], avatars: [...]}; stdout: JSON {rendered, calls, avatars}.
const vm = require('vm');
const input = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const match = input.html.match(/<script nonce="[^"]*">([\s\S]*?)<\/script>/);
if (!match) { console.log(JSON.stringify({error: 'no nonce script'})); process.exit(0); }

const elements = {};
function makeElement(id) {
  const listeners = {};
  const el = {
    id, innerHTML: '', textContent: '', value: '', type: 'text', placeholder: '', disabled: false,
    dataset: {}, style: {}, children: [],
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    addEventListener(type, handler, capture) { (listeners[type] = listeners[type] || []).push({ handler, capture }); },
    appendChild(child) { this.children.push(child); return child; },
    append(...nodes) { nodes.forEach(n => this.children.push(n)); },
    remove() {}, focus() {}, setAttribute() {}, getAttribute() { return null; },
    querySelector() { return null; }, closest() { return null; },
    _listeners: listeners,
  };
  return el;
}
const document = {
  getElementById(id) { return elements[id] || (elements[id] = makeElement(id)); },
  createElement(tag) { const el = makeElement('<' + tag + '>'); el.tagName = tag.toUpperCase(); return el; },
  createTextNode(text) { return { textContent: String(text) }; },
};
const calls = [];
const store = {};
const sandbox = {
  document,
  window: {
    location: { protocol: 'https:', origin: 'https://truyen.achatvn.com' },
    addEventListener() {},
    sessionStorage: null,
  },
  sessionStorage: { getItem: k => store[k] || null, setItem: (k, v) => { store[k] = v; }, removeItem: k => { delete store[k]; } },
  fetch: async () => ({ ok: true, status: 200, json: async () => [], text: async () => '' }),
  navigator: { clipboard: { writeText: async () => {} } },
  requestAnimationFrame: fn => fn(),
  setTimeout: () => 0,
  alert: m => calls.push(['alert', m]),
  confirm: m => { calls.push(['confirm', m]); return false; },
  console,
  URL,
  encodeURIComponent,
};
sandbox.window.sessionStorage = sandbox.sessionStorage;
vm.createContext(sandbox);
vm.runInContext(match[1], sandbox);

// Gọi renderUserList(users) rồi lấy HTML đã dựng; thử cả đường uỷ quyền click.
vm.runInContext('globalThis.__out = (users) => { renderUserList(users); return userListContainer; }; globalThis.safeAvatarUrl = safeAvatarUrl;', sandbox);
const container = sandbox.__out(input.users);
const rendered = container.innerHTML;

// Mô phỏng click vào nút trong thẻ người dùng đầu tiên: stub window.openChangePasswordModal / deleteUserPrompt.
sandbox.window.openChangePasswordModal = name => calls.push(['openChangePasswordModal', name]);
sandbox.window.deleteUserPrompt = name => calls.push(['deleteUserPrompt', name]);
const clickHandlers = (container._listeners.click || []).map(l => l.handler);
for (const user of input.users.slice(0, 1)) {
  for (const action of ['change-password', 'delete']) {
    const card = { getAttribute: n => (n === 'data-username' ? user.username : null) };
    const button = {
      getAttribute: n => (n === 'data-action' ? action : null),
      closest: sel => (sel === '.user-card' ? card : null),
    };
    const event = { target: { closest: sel => (sel === 'button[data-action]' ? button : null) } };
    clickHandlers.forEach(h => h(event));
  }
}
const avatars = (input.avatars || []).map(url => sandbox.safeAvatarUrl(url));
console.log(JSON.stringify({ rendered, calls, avatars }));
