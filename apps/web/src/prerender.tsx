import { renderToReadableStream } from 'react-dom/server';
import { App } from './App';

// Node24 supports Web Streams. Await all lazy modules before emitting static HTML.
export async function renderPage(): Promise<string> {
  const stream = await renderToReadableStream(<App />);
  await stream.allReady;
  return new Response(stream).text();
}
