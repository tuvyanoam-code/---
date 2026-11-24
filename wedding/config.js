/*
 * ההגדרות של החתונה — זה הקובץ היחיד שצריך לערוך.  ·  Wedding settings — the only file you need to edit.
 * כל טקסט שמופיע לאורחים כתוב פעמיים: he = עברית, en = English.
 * אחרי שינוי: שמרו ודחפו ל-GitHub. האתר מתעדכן לבד תוך דקה-שתיים.
 */
window.WEDDING = {
  // השפה שבה האתר נפתח כברירת מחדל: 'he' או 'en'. האורחים יכולים להחליף בכפתור למעלה.
  defaultLang: 'he',

  // שמות החתן והכלה, כפי שיופיעו בראש העמוד ובהודעות
  couple: { he: ['טוביה נעם', 'שפרה'], en: ['Tuvya Noam', 'Shifra'] },

  // תאריך ושעת קבלת הפנים (שעון ישראל), בפורמט YYYY-MM-DDTHH:MM — משמש לספירה לאחור וליומן
  date: '2026-12-29T18:00',

  // התאריך כפי שהוא כתוב בהזמנה
  dateLine: { he: 'יום שלישי, י״ט טבת ה׳תשפ״ז', en: 'Tuesday, December 29, 2026' },
  dateSub: { he: 'אור לכ׳ טבת · 29 בדצמבר 2026', en: '19th of Tevet 5787' },
  // התאריך בתוך הודעות השיתוף
  dateText: { he: 'ביום שלישי, י״ט טבת ה׳תשפ״ז', en: 'on Tuesday, the 19th of Tevet, December 29, 2026' },

  venue: {
    name: { he: 'גן האירועים ׳אלה׳', en: 'Ela Events' },
    address: { he: 'רחוב האלופים 1, נס ציונה', en: "HaAlufim 1, Nes Tziona" },
    // לא חובה: קישורים מדויקים. אם ריק — מחפשים לפי השם והכתובת.
    waze: '',
    maps: '',
  },

  // אישור הגעה
  rsvp: {
    deadline: '',                // עד מתי מבקשים לאשר, למשל '2026-12-15' (ריק = בלי תאריך)
    maxGuests: 10,               // מקסימום משתתפים באישור אחד
    askPhone: true,              // לבקש מספר טלפון
    askNote: true,               // לאפשר לכתוב ברכה או הערה
  },

  // קובץ ההזמנה. מחליפים את invitation.pdf בתיקייה הזו — וזהו.
  invitationPdf: { he: 'invitation.pdf', en: '' },
  // אילו עמודים מוצגים בכל שפה ובאיזה סדר (הראשון הוא גם התמונה בתצוגה המקדימה בוואטסאפ).
  // קובץ ה-PDF להורדה תמיד מלא.
  pageOrder: { he: [1, 2, 3], en: [4] },

  // הכתובת שבה האתר מתפרסם (משמשת לקישורי השיתוף ולתצוגה המקדימה בוואטסאפ)
  siteUrl: 'https://tuvyanoam-code.github.io/---/',

  // כתובת ה-Web App של Google Apps Script (ראו README.md).
  // כל עוד זה ריק האתר עובד במצב הדגמה: הנתונים נשמרים רק בדפדפן שלכם.
  backendUrl: 'https://script.google.com/macros/s/AKfycbxJgYGFh3sfd0c2GI9XxJq4fHH2A32M4sixlgD5R0S8Y_J3q0ss1_XNt35MGD6Zhs1n/exec',
  // הגיליון שבו נשמרים הנתונים (נפתח רק בחשבון גוגל שלכם). לוח הניהול מציע אותו כשהחיבור חוסם את קריאת הנתונים.
  sheetUrl: 'https://docs.google.com/spreadsheets/d/1tvAXSrpocDlLXmDGuvio9qT1wUDUULaU7sQjbazLSSA/edit',

  // נוסח הודעות השיתוף. {couple} = השמות, {link} = הקישור, {name} = שם המוזמן, {date} = התאריך
  shareText: {
    he: 'בעזהי״ת\nבשמחה ובהודיה לה׳ יתברך, הנכם מוזמנים לשמחת נישואי {couple}, שתתקיים אי״ה {date}, בשעה טובה ומוצלחת.\nלעיון בהזמנה ולאישור השתתפות:\n{link}',
    en: 'B"H\nWith immense gratitude to Hashem, you are warmly invited to the wedding of {couple}, {date}.\nTo view the invitation and RSVP:\n{link}',
  },
  personalShareText: {
    he: 'בעזהי״ת\nלכבוד {name},\nבשמחה ובהודיה לה׳ יתברך, נתכבד בהשתתפותכם בשמחת נישואי {couple}, שתתקיים אי״ה {date}, בשעה טובה ומוצלחת.\nלעיון בהזמנה ולאישור השתתפות:\n{link}',
    en: 'B"H\nDear {name},\nWith immense gratitude to Hashem, we would be honored by your presence at the wedding of {couple}, {date}.\nTo view the invitation and RSVP:\n{link}',
  },

  // קידומת מדינה לטלפונים שנרשמים בלי קידומת (050… → 97250…)
  countryCode: '972',

  // הצדדים ברשימת המוזמנים
  sides: { he: ['צד החתן', 'צד הכלה', 'משותף'], en: ["Groom's side", "Bride's side", 'Both'] },
};
