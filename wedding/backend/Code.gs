/**
 * צד השרת של אתר החתונה — Google Apps Script שמחובר לגיליון Google Sheets.
 * כל אישור הגעה, כל צפייה וכל שיתוף נרשמים בגיליון, ולוח הניהול קורא משם.
 *
 * התקנה (פעם אחת, כ-5 דקות) — ההוראות המלאות ב-README.md:
 *  1. צרו גיליון חדש ב-Google Sheets ← תוספים (Extensions) ← Apps Script.
 *  2. מחקו את מה שיש שם, הדביקו את כל הקובץ הזה, ושנו את הסיסמה כאן למטה.
 *  3. הריצו פעם אחת את הפונקציה setup (ואשרו הרשאות).
 *  4. פריסה (Deploy) ← פריסה חדשה ← Web app: Execute as "Me", Who has access "Anyone".
 *  5. העתיקו את כתובת ה-Web app (מסתיימת ב-/exec) לשדה backendUrl בקובץ config.js.
 */

// סיסמה ללוח הניהול. שנו אותה לפני הפריסה!
const ADMIN_PASSWORD = 'change-me';

const SHEETS = {
  rsvps: {
    name: 'אישורי הגעה',
    cols: ['id', 'updated', 'name', 'status', 'count', 'phone', 'note', 'guest', 'src', 'device', 'created', 'edits'],
    headers: ['מזהה', 'עודכן', 'שם', 'תשובה', 'מספר מגיעים', 'טלפון', 'ברכה / הערה', 'מזהה מוזמן', 'מקור', 'מכשיר', 'נוצר', 'מספר עדכונים'],
  },
  guests: {
    name: 'מוזמנים',
    cols: ['id', 'name', 'phone', 'side', 'invited', 'notes', 'created', 'sent'],
    headers: ['מזהה', 'שם', 'טלפון', 'צד', 'מוזמנים', 'הערות', 'נוסף', 'נשלחה הזמנה'],
  },
  events: {
    name: 'פעילות',
    cols: ['t', 'type', 'visitor', 'guest', 'src', 'device', 'detail'],
    headers: ['זמן', 'פעולה', 'מבקר', 'מזהה מוזמן', 'מקור', 'מכשיר', 'פרטים'],
  },
};
const TIME_COLS = { updated: 1, created: 1, sent: 1, t: 1 };
const STATUS_HE = { yes: 'מגיעים', no: 'לא מגיעים', maybe: 'אולי' };
const STATUS_EN = { 'מגיעים': 'yes', 'לא מגיעים': 'no', 'אולי': 'maybe' };
const EVENT_TYPES = ['view', 'open_invite', 'download_pdf', 'share', 'copy_link', 'nav', 'calendar', 'rsvp', 'rsvp_start'];

/* ---------------- entry points ---------------- */

function doGet() {
  return json({ ok: true, service: 'wedding', time: Date.now() });
}

function doPost(e) {
  let body;
  try {
    body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
  } catch (err) {
    return json({ ok: false, error: 'bad_json' });
  }
  try {
    switch (body.action) {
      case 'track':
        return json(track(body.events));
      case 'rsvp':
        return json(saveRsvp(body.rsvp, false));
      case 'guest':
        return json(publicGuest(body.id));
      case 'admin':
        if (String(body.password || '') !== ADMIN_PASSWORD || ADMIN_PASSWORD === 'change-me') {
          Utilities.sleep(600);
          return json({ ok: false, error: ADMIN_PASSWORD === 'change-me' ? 'password_not_set' : 'bad_password' });
        }
        return json(admin(body));
      default:
        return json({ ok: false, error: 'unknown_action' });
    }
  } catch (err) {
    console.error(err);
    return json({ ok: false, error: 'server' });
  }
}

/* ---------------- guest-side ---------------- */

function track(events) {
  if (!Array.isArray(events) || !events.length) return { ok: true };
  const now = Date.now();
  const rows = events.slice(0, 40).filter((ev) => ev && EVENT_TYPES.indexOf(ev.type) >= 0).map((ev) => {
    let t = Number(ev.t) || now;
    if (Math.abs(t - now) > 10 * 60 * 1000) t = now; // never trust a client clock that is far off
    return toRow('events', {
      t: t,
      type: ev.type,
      visitor: clean(ev.visitor, 24),
      guest: clean(ev.guest, 64),
      src: clean(ev.src, 20),
      device: clean(ev.device, 12),
      detail: clean(ev.detail, 200),
    });
  });
  if (!rows.length) return { ok: true };
  withLock(() => {
    const sh = sheet('events');
    sh.getRange(sh.getLastRow() + 1, 1, rows.length, rows[0].length).setValues(rows);
  });
  return { ok: true };
}

function saveRsvp(input, byAdmin) {
  if (!input) return { ok: false, error: 'missing' };
  const status = ['yes', 'no', 'maybe'].indexOf(input.status) >= 0 ? input.status : 'yes';
  const r = {
    id: clean(input.id, 64),
    name: clean(input.name, 80),
    status: status,
    count: status === 'no' ? 0 : Math.max(1, Math.min(50, parseInt(input.count, 10) || 1)),
    phone: clean(input.phone, 30),
    note: clean(input.note, 600),
    guest: clean(input.guest, 64),
    src: clean(input.src, 20) || (byAdmin ? 'admin' : ''),
    device: clean(input.device, 12),
  };
  if (!r.id || !r.name) return { ok: false, error: 'missing' };
  let saved;
  withLock(() => {
    const sh = sheet('rsvps');
    const now = Date.now();
    const rowIndex = findRow(sh, r.id);
    if (rowIndex) {
      const prev = fromRow('rsvps', sh.getRange(rowIndex, 1, 1, SHEETS.rsvps.cols.length).getValues()[0]);
      saved = Object.assign(prev, r, { updated: now, edits: (Number(prev.edits) || 0) + 1 });
      sh.getRange(rowIndex, 1, 1, SHEETS.rsvps.cols.length).setValues([toRow('rsvps', saved)]);
    } else {
      saved = Object.assign(r, { updated: now, created: now, edits: 0 });
      sh.appendRow(toRow('rsvps', saved));
    }
  });
  return { ok: true, rsvp: saved };
}

function publicGuest(id) {
  id = clean(id, 64);
  if (!id) return { ok: true, guest: null };
  const sh = sheet('guests');
  const row = findRow(sh, id);
  if (!row) return { ok: true, guest: null };
  const g = fromRow('guests', sh.getRange(row, 1, 1, SHEETS.guests.cols.length).getValues()[0]);
  // Only what the invitation page needs to greet the guest.
  return { ok: true, guest: { id: g.id, name: g.name, invited: Number(g.invited) || 0 } };
}

/* ---------------- admin ---------------- */

function admin(body) {
  switch (body.op) {
    case 'data':
      return {
        ok: true,
        rsvps: readAll('rsvps'),
        guests: readAll('guests'),
        events: readAll('events'),
        sheetUrl: SpreadsheetApp.getActiveSpreadsheet().getUrl(),
      };
    case 'saveGuests': {
      const list = (body.guests || []).slice(0, 2000);
      withLock(() => {
        const sh = sheet('guests');
        const ids = idIndex(sh);
        const now = Date.now();
        const appends = [];
        list.forEach((input) => {
          const g = {
            id: clean(input.id, 64) || Utilities.getUuid().slice(0, 8),
            name: clean(input.name, 80),
            phone: clean(input.phone, 30),
            side: clean(input.side, 40),
            invited: Math.max(0, Math.min(50, parseInt(input.invited, 10) || 0)),
            notes: clean(input.notes, 300),
            created: Number(input.created) || now,
            sent: Number(input.sent) || 0,
          };
          if (!g.name) return;
          if (ids[g.id]) sh.getRange(ids[g.id], 1, 1, SHEETS.guests.cols.length).setValues([toRow('guests', g)]);
          else appends.push(toRow('guests', g));
        });
        if (appends.length) sh.getRange(sh.getLastRow() + 1, 1, appends.length, appends[0].length).setValues(appends);
      });
      return { ok: true };
    }
    case 'deleteGuest':
      withLock(() => deleteById(sheet('guests'), clean(body.id, 64)));
      return { ok: true };
    case 'markSent': {
      const ids = body.ids || [];
      const now = Date.now();
      withLock(() => {
        const sh = sheet('guests');
        const index = idIndex(sh);
        const col = SHEETS.guests.cols.indexOf('sent') + 1;
        ids.forEach((id) => {
          if (index[id]) sh.getRange(index[id], col).setValue(new Date(now));
        });
      });
      return { ok: true };
    }
    case 'saveRsvp':
      return saveRsvp(body.rsvp, true);
    case 'deleteRsvp':
      withLock(() => deleteById(sheet('rsvps'), clean(body.id, 64)));
      return { ok: true };
    default:
      return { ok: false, error: 'unknown_op' };
  }
}

/* ---------------- sheet helpers ---------------- */

function setup() {
  Object.keys(SHEETS).forEach((key) => sheet(key));
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const def = ss.getSheetByName('Sheet1') || ss.getSheetByName('גיליון1');
  if (def && ss.getSheets().length > 1 && def.getLastRow() === 0) ss.deleteSheet(def);
  return 'מוכן! עכשיו: פריסה ← פריסה חדשה ← Web app';
}

function sheet(key) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const def = SHEETS[key];
  let sh = ss.getSheetByName(def.name);
  if (!sh) {
    sh = ss.insertSheet(def.name);
    sh.setRightToLeft(true);
    sh.getRange(1, 1, 1, def.headers.length).setValues([def.headers]).setFontWeight('bold').setBackground('#fde7ef');
    sh.setFrozenRows(1);
    def.cols.forEach((c, i) => {
      if (TIME_COLS[c]) sh.getRange(2, i + 1, sh.getMaxRows() - 1, 1).setNumberFormat('dd/MM/yyyy HH:mm');
    });
    // Ids and phones stay text so Sheets never turns 0501234567 into a number.
    ['id', 'phone', 'guest', 'visitor'].forEach((c) => {
      const i = def.cols.indexOf(c);
      if (i >= 0) sh.getRange(2, i + 1, sh.getMaxRows() - 1, 1).setNumberFormat('@');
    });
  }
  return sh;
}

function toRow(key, obj) {
  return SHEETS[key].cols.map((c) => {
    const v = obj[c];
    if (TIME_COLS[c]) return v ? new Date(Number(v) || v) : '';
    if (c === 'status') return STATUS_HE[v] || v || '';
    return safeCell(v);
  });
}

function fromRow(key, row) {
  const o = {};
  SHEETS[key].cols.forEach((c, i) => {
    let v = row[i];
    if (TIME_COLS[c]) v = v instanceof Date ? v.getTime() : Number(v) || 0;
    else if (c === 'status') v = STATUS_EN[v] || v;
    else if (c === 'count' || c === 'invited' || c === 'edits') v = Number(v) || 0;
    else v = String(v == null ? '' : v).replace(/^'/, '');
    o[c] = v;
  });
  return o;
}

function readAll(key) {
  const sh = sheet(key);
  const n = sh.getLastRow() - 1;
  if (n < 1) return [];
  return sh.getRange(2, 1, n, SHEETS[key].cols.length).getValues().filter((r) => r.join('') !== '').map((r) => fromRow(key, r));
}

function idIndex(sh) {
  const n = sh.getLastRow() - 1;
  const out = {};
  if (n < 1) return out;
  sh.getRange(2, 1, n, 1).getValues().forEach((r, i) => {
    out[String(r[0]).replace(/^'/, '')] = i + 2;
  });
  return out;
}

function findRow(sh, id) {
  if (!id) return 0;
  const n = sh.getLastRow() - 1;
  if (n < 1) return 0;
  const hit = sh.getRange(2, 1, n, 1).createTextFinder(id).matchEntireCell(true).findNext();
  return hit ? hit.getRow() : 0;
}

function deleteById(sh, id) {
  const row = findRow(sh, id);
  if (row) sh.deleteRow(row);
}

function withLock(fn) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    fn();
  } finally {
    lock.releaseLock();
  }
}

function clean(v, max) {
  return String(v == null ? '' : v).trim().slice(0, max || 300);
}

// A value a guest typed must never run as a spreadsheet formula.
function safeCell(v) {
  if (v == null) return '';
  if (typeof v === 'number') return v;
  const s = String(v);
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
