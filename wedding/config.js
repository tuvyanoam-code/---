/*
 * ההגדרות של החתונה — זה הקובץ היחיד שצריך לערוך.  ·  Wedding settings — the only file you need to edit.
 * כל טקסט שמופיע לאורחים כתוב פעמיים: he = עברית, en = English.
 * אחרי שינוי: שמרו ודחפו ל-GitHub. האתר מתעדכן לבד תוך דקה-שתיים.
 */
window.WEDDING = {
  // השפה שבה האתר נפתח כברירת מחדל: 'he' או 'en'. האורחים יכולים להחליף בכפתור למעלה.
  defaultLang: 'he',

  // השמות כפי שיופיעו בראש העמוד ובהודעות השיתוף
  couple: { he: ['נועה', 'איתי'], en: ['Noa', 'Itai'] },

  // תאריך ושעת קבלת הפנים (שעון ישראל), בפורמט YYYY-MM-DDTHH:MM
  date: '2027-06-17T19:30',

  // התאריך העברי מחושב אוטומטית. אפשר לכתוב נוסח משלכם, למשל: { he: 'י״א בסיוון תשפ״ז', en: '11 Sivan 5787' }
  hebrewDate: '',

  // סדר הערב (אפשר למחוק או להוסיף שורות)
  schedule: [
    { time: '19:30', label: { he: 'קבלת פנים', en: 'Reception' } },
    { time: '20:30', label: { he: 'חופה וקידושין', en: 'Ceremony' } },
  ],

  venue: {
    name: { he: 'גן האירועים', en: 'The Garden Venue' },
    address: { he: 'רחוב הדוגמה 1, תל אביב', en: '1 Example St, Tel Aviv' },
    // לא חובה: קישורים מדויקים. אם ריק — מחפשים לפי הכתובת.
    waze: '',
    maps: '',
  },

  // אישור הגעה
  rsvp: {
    deadline: '2027-06-01',      // עד מתי מבקשים לאשר (ריק = בלי תאריך)
    maxGuests: 10,               // מקסימום אורחים באישור אחד
    askPhone: true,              // לבקש מספר טלפון
    askNote: true,               // לאפשר לכתוב ברכה או הערה
  },

  // קובץ ההזמנה. מחליפים את invitation.pdf בתיקייה הזו — וזהו.
  // יש לכם גם הזמנה באנגלית? שימו אותה כאן (למשל 'invitation-en.pdf'). ריק = האורחים באנגלית יראו את אותה הזמנה.
  invitationPdf: { he: 'invitation.pdf', en: '' },

  // הכתובת שבה האתר מתפרסם (משמשת לקישורי השיתוף ולתצוגה המקדימה בוואטסאפ)
  siteUrl: 'https://tuvyanoam-code.github.io/---/',

  // כתובת ה-Web App של Google Apps Script (ראו README.md).
  // כל עוד זה ריק האתר עובד במצב הדגמה: הנתונים נשמרים רק בדפדפן שלכם.
  backendUrl: '',

  // נוסח הודעות השיתוף. {couple} = השמות, {link} = הקישור, {name} = שם המוזמן, {date} = התאריך
  shareText: {
    he: 'הוזמנתם לחתונה של {couple}! 🤍\n{date}\nההזמנה ואישור הגעה בלחיצה:\n{link}',
    en: "You're invited to the wedding of {couple}! 🤍\n{date}\nInvitation & RSVP:\n{link}",
  },
  personalShareText: {
    he: 'היי {name}! 🤍\nנשמח מאוד לראות אתכם בחתונה שלנו, {date}.\nההזמנה ואישור הגעה כאן:\n{link}\n{couple}',
    en: "Hi {name}! 🤍\nWe'd love to see you at our wedding, {date}.\nInvitation & RSVP:\n{link}\n{couple}",
  },

  // קידומת מדינה לטלפונים שנרשמים בלי קידומת (050… → 97250…)
  countryCode: '972',

  // הצדדים ברשימת המוזמנים. ריק = השמות שלכם + 'משותף'. למשל: { he: ['חתן', 'כלה', 'משותף'], en: ['Groom', 'Bride', 'Both'] }
  sides: { he: [], en: [] },
};
