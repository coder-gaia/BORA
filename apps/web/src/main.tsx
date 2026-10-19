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
  if (root.hasChildNodes()) hydrateRoot(root, content);
  else createRoot(root).render(content);
};
// Preserve the first paint of the public HTML before attaching interactive views.
requestAnimationFrame(() => requestAnimationFrame(mount));
