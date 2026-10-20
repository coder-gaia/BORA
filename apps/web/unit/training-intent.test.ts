import assert from 'node:assert/strict';
import test from 'node:test';
import {
  forgetTrainingIntents,
  readTrainingIntent,
  saveTrainingIntent,
} from '../src/lib/training-intent.ts';

function fixture() {
  const values = new Map<string, string>();
  const store = {
    get length() {
      return values.size;
    },
    key: (index: number) => [...values.keys()][index] ?? null,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: (key: string) => {
      values.delete(key);
    },
  };
  Object.defineProperty(globalThis, 'window', {
    value: { sessionStorage: store },
    configurable: true,
  });
  return values;
}

test('somente a chave da intenção é salva e cada usuário tem seu escopo', () => {
  const values = fixture();
  saveTrainingIntent('a', 'intent-a');
  saveTrainingIntent('b', 'intent-b');
  assert.equal(readTrainingIntent('a'), 'intent-a');
  assert.equal(readTrainingIntent('b'), 'intent-b');
  assert.deepEqual([...values.values()], ['intent-a', 'intent-b']);
  saveTrainingIntent('a', null);
  assert.equal(readTrainingIntent('a'), null);
  assert.equal(readTrainingIntent('b'), 'intent-b');
});

test('contexto revogado limpa intenções e preserva outros dados do navegador', () => {
  const values = fixture();
  values.set('other', 'keep');
  saveTrainingIntent('a', 'intent-a');
  saveTrainingIntent('b', 'intent-b');
  forgetTrainingIntents();
  assert.deepEqual([...values], [['other', 'keep']]);
});

test('storage bloqueado ou revogado não derruba a interface', () => {
  Object.defineProperty(globalThis, 'window', {
    value: {
      get sessionStorage() {
        throw new Error('blocked');
      },
    },
    configurable: true,
  });
  assert.equal(readTrainingIntent('a'), null);
  assert.doesNotThrow(() => saveTrainingIntent('a', 'intent'));
  assert.doesNotThrow(forgetTrainingIntents);
  Object.defineProperty(globalThis, 'window', {
    value: {
      sessionStorage: {
        getItem() {
          throw new Error('blocked');
        },
        setItem() {
          throw new Error('blocked');
        },
        removeItem() {
          throw new Error('blocked');
        },
      },
    },
    configurable: true,
  });
  assert.equal(readTrainingIntent('a'), null);
  assert.doesNotThrow(() => saveTrainingIntent('a', 'intent'));
  assert.doesNotThrow(() => saveTrainingIntent('a', null));
});
