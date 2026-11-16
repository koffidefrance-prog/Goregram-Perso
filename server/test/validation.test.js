import test from 'node:test';
import assert from 'node:assert/strict';
import { isPhoneE164, cleanMessage, isValidMessage } from '../src/validation.js';

test('phone numbers must use E.164 format', () => {
  assert.equal(isPhoneE164('+2250102030405'), true);
  assert.equal(isPhoneE164('0102030405'), false);
  assert.equal(isPhoneE164('+0123456789'), false);
});
test('message validation trims and limits content', () => {
  assert.equal(cleanMessage('  salut  '), 'salut');
  assert.equal(isValidMessage('salut'), true);
  assert.equal(isValidMessage('   '), false);
  assert.equal(isValidMessage('x'.repeat(10001)), false);
});
