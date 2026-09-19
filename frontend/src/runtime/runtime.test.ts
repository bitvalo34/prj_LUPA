import { describe, expect, it, vi } from 'vitest';
import { BitmapBudget } from './BitmapBudget';
import { DeliveryLedger } from './DeliveryLedger';

describe('DeliveryLedger', () => {
  it('envía un solo RELEASE por entrega y conexión', () => {
    const ledger = new DeliveryLedger();
    const sender = vi.fn();
    expect(ledger.register(1, 7)).toBe(true);
    expect(ledger.releaseOnce(1, 7, 'displayed', sender)).toBe(true);
    expect(ledger.releaseOnce(1, 7, 'discarded', sender)).toBe(false);
    expect(sender).toHaveBeenCalledTimes(1);
    expect(sender).toHaveBeenCalledWith(1, 'displayed');
    expect(ledger.register(1, 7)).toBe(false);
  });

  it('no confirma una entrega desde otra conexión', () => {
    const ledger = new DeliveryLedger();
    const sender = vi.fn();
    ledger.register(4, 3);
    expect(ledger.releaseOnce(4, 9, 'discarded', sender)).toBe(false);
    expect(sender).not.toHaveBeenCalled();
  });
});

describe('BitmapBudget', () => {
  it('reserva, confirma y libera memoria RGBA administrada', () => {
    const budget = new BitmapBudget();
    expect(budget.reserve('a', 256 * 256 * 4)).toBe(true);
    const bytes = budget.commit('a', 'detail');
    expect(bytes).toBe(262144);
    expect(budget.snapshot().detailBytes).toBe(262144);
    budget.removeStored('detail', 262144);
    expect(budget.snapshot().managedBytes).toBe(0);
  });

  it('rechaza transitorios por encima de 16 MiB', () => {
    const budget = new BitmapBudget();
    expect(budget.reserve('huge', 16 * 1024 * 1024 + 4)).toBe(false);
  });

  it('comparte los 16 MiB entre contexto almacenado y transitorios', () => {
    const budget = new BitmapBudget();
    expect(budget.reserve('context', 4 * 1024 * 1024)).toBe(true);
    expect(budget.commit('context', 'context')).toBe(4 * 1024 * 1024);
    expect(budget.reserve('too-much', 13 * 1024 * 1024)).toBe(false);
    expect(budget.reserve('fits', 12 * 1024 * 1024)).toBe(true);
  });
});
