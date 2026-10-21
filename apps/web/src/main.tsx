import '@fontsource-variable/inter/wght.css';
import { StrictMode } from 'react';
import { createRoot, hydrateRoot } from 'react-dom/client';
import { App } from './App';
import './index.css';

const content = (
  <StrictMode>
    <App />
  </StrictMode>
);

const root = document.getElementById('root')!;
const mount = () => {
  if (
    root.hasChildNodes() &&
    !window.location.pathname.startsWith('/rastreio/') &&
    window.location.pathname !== '/como-funciona' &&
    window.location.pathname !== '/loja' &&
    window.location.pathname !== '/entregador' &&
    window.location.pathname !== '/operacao'
  )
    hydrateRoot(root, content);
  else createRoot(root).render(content);
};
// Preserve the first paint of the public HTML before attaching interactive views.
requestAnimationFrame(() => requestAnimationFrame(mount));
