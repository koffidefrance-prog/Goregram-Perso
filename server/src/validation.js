export const isPhoneE164 = value => /^\+[1-9]\d{7,14}$/.test(String(value || ''));
export const cleanMessage = value => String(value || '').trim();
export const isValidMessage = value => value.length > 0 && value.length <= 10000;
