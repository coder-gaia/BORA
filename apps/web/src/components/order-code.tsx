import { useEffect, useRef, useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { Button } from '@/components/ui/button';

export function CopyValue({ value, label }: { value: string; label: string }) {
  const [message, setMessage] = useState('');
  const timer = useRef<number | undefined>(undefined);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      window.clearTimeout(timer.current);
    };
  }, []);
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      if (!alive.current) return;
      setMessage('Copiado');
    } catch {
      if (!alive.current) return;
      setMessage('Selecione o texto e copie manualmente.');
    }
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setMessage(''), 4_000);
  }
  return (
    <span className="copy-value">
      <Button variant="outline" type="button" onClick={() => void copy()} aria-label={label}>
        {message === 'Copiado' ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
        {label}
      </Button>
      <span className="copy-feedback" aria-live="polite">
        {message}
      </span>
    </span>
  );
}

export function OrderCode({ code }: { code: string }) {
  return (
    <div className="order-code-block">
      <span className="order-code-caption">Código do pedido</span>
      <code className="order-reference">{code}</code>
      <CopyValue value={code} label="Copiar código do pedido" />
    </div>
  );
}
