import { useEffect, useMemo, useRef, useState } from 'react';
import type { Catalog, CatalogImage } from '../protocol/types';
import { loadCatalog } from '../runtime/catalog';
import { LupaClient, type ClientSnapshot } from '../runtime/LupaClient';
import { SampleTransport, SAMPLE_CATALOG } from '../runtime/SampleTransport';

const INITIAL_SNAPSHOT: ClientSnapshot = {
  phase: 'disconnected',
  connectionId: 0,
  selectedImageId: null,
  manifest: null,
  plan: null,
  welcome: null,
  activeEpoch: 0,
  receivedTiles: 0,
  drawnTiles: 0,
  discardedTiles: 0,
  failedTiles: 0,
  releases: 0,
  pendingDecodes: 0,
  serverDone: false,
  doneSentTiles: null,
  managedBitmapBytes: 0,
  error: null,
  trace: []
};

export function App() {
  const sampleMode = useMemo(() => new URLSearchParams(window.location.search).get('sample') === '1', []);
  const client = useMemo(
    () => new LupaClient(sampleMode ? () => new SampleTransport() : undefined),
    [sampleMode]
  );
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const [snapshot, setSnapshot] = useState<ClientSnapshot>(INITIAL_SNAPSHOT);
  const [catalog, setCatalog] = useState<Catalog | null>(sampleMode ? SAMPLE_CATALOG : null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [catalogBusy, setCatalogBusy] = useState(!sampleMode);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);

  async function refreshCatalog() {
    if (sampleMode) {
      setCatalog(SAMPLE_CATALOG);
      setCatalogError(null);
      return;
    }
    setCatalogBusy(true);
    setCatalogError(null);
    try {
      setCatalog(await loadCatalog());
    } catch (error) {
      setCatalog(null);
      setCatalogError(error instanceof Error ? error.message : 'No se pudo cargar el catálogo');
    } finally {
      setCatalogBusy(false);
    }
  }

  useEffect(() => {
    const unsubscribe = client.subscribe(setSnapshot);
    if (canvasRef.current) client.attachCanvas(canvasRef.current);
    client.connect();
    if (!sampleMode) void refreshCatalog();
    return () => {
      unsubscribe();
      client.destroy();
    };
  }, [client, sampleMode]);

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect;
      if (!rect) return;
      client.setViewport(rect.width, rect.height, window.devicePixelRatio || 1);
    });
    observer.observe(stage);
    return () => observer.disconnect();
  }, [client]);

  function selectImage(image: CatalogImage) {
    client.selectImage(image);
  }

  return (
    <div className="app-shell">
      <div className="ambient-orbit" aria-hidden="true">
        <span className="orbit orbit-a" />
        <span className="orbit orbit-b" />
        <span className="orbit-sphere" />
      </div>

      <header className="top-console">
        <div className="brand-lockup">
          <PrismMark />
          <div>
            <p className="eyebrow">LECTURA ULTRARESOLUTIVA</p>
            <h1>LUPA <span>64</span></h1>
            <p className="tagline">Explorador de mundos visuales</p>
          </div>
        </div>

        <div className="header-actions">
          {sampleMode && <span className="sample-badge">MODO MUESTRA</span>}
          <StatusLamp phase={snapshot.phase} />
          <button className="console-button small" onClick={() => client.reconnect()}>
            Reconectar
          </button>
        </div>
      </header>

      <main className="console-grid">
        <aside className="cartridge-bay" aria-label="Catálogo de imágenes">
          <div className="panel-title-row">
            <div>
              <p className="eyebrow">BAHÍA A</p>
              <h2>Cartuchos</h2>
            </div>
            {!sampleMode && (
              <button className="icon-button" onClick={() => void refreshCatalog()} aria-label="Recargar catálogo">
                ↻
              </button>
            )}
          </div>

          {catalogBusy && <div className="catalog-message">Leyendo catálogo…</div>}
          {catalogError && (
            <div className="catalog-message error-card">
              <strong>Catálogo no disponible</strong>
              <span>{catalogError}</span>
              <button className="console-button" onClick={() => void refreshCatalog()}>Reintentar</button>
            </div>
          )}
          {catalog && catalog.images.length === 0 && (
            <div className="catalog-message">No hay imágenes publicadas todavía.</div>
          )}

          <div className="cartridge-list">
            {catalog?.images.map((image, index) => (
              <Cartridge
                key={image.imageId + image.imageVersion}
                image={image}
                index={index}
                selected={snapshot.selectedImageId === image.imageId}
                onSelect={() => selectImage(image)}
              />
            ))}
          </div>
        </aside>

        <section className="viewer-console" aria-label="Visor LUPA">
          <div className="viewer-bezel">
            <div className="bezel-top">
              <span className="engraved">OBSERVATION PORT // CANVAS 2D</span>
              <div className="bezel-lights" aria-hidden="true"><i/><i/><i/></div>
            </div>
            <div className="screen-wrap">
              <div className="screen-stage" ref={stageRef}>
                <canvas ref={canvasRef} aria-label="Imagen científica compuesta progresivamente" />
                {!snapshot.manifest && (
                  <div className="screen-idle">
                    <div className="idle-prism" aria-hidden="true" />
                    <strong>Selecciona un cartucho</strong>
                    <span>La miniatura real llegará como TILE z=0.</span>
                  </div>
                )}
                {snapshot.phase === 'error' && (
                  <div className="screen-error" role="alert">
                    <strong>La señal se interrumpió</strong>
                    <span>{snapshot.error}</span>
                  </div>
                )}
              </div>
            </div>
            <div className="viewer-controls">
              <StatusReadout snapshot={snapshot} />
            </div>
          </div>
        </section>

        <aside className="telemetry-panel">
          <div className="panel-title-row">
            <div>
              <p className="eyebrow">TELEMETRÍA</p>
              <h2>Señal</h2>
            </div>
            <span className="panel-knob" aria-hidden="true" />
          </div>
          <Metric label="Conexión" value={'#' + snapshot.connectionId} />
          <Metric label="Época" value={String(snapshot.activeEpoch || '—')} />
          <Metric label="TILE recibidos" value={String(snapshot.receivedTiles)} />
          <Metric label="Dibujados" value={String(snapshot.drawnTiles)} />
          <Metric label="Descartados" value={String(snapshot.discardedTiles)} />
          <Metric label="RELEASE" value={String(snapshot.releases)} />
          <Metric label="Decodificando" value={String(snapshot.pendingDecodes)} />
          <Metric label="Bitmaps LUPA" value={formatBytes(snapshot.managedBitmapBytes)} />
          <p className="memory-note">Memoria administrada por LUPA; no representa toda la RAM/GPU del navegador.</p>

          <button
            className="console-button secondary"
            onClick={() => setDiagnosticsOpen((value) => !value)}
            aria-expanded={diagnosticsOpen}
          >
            {diagnosticsOpen ? 'Ocultar diagnóstico' : 'Abrir diagnóstico'}
          </button>
          <button className="console-button secondary" onClick={() => client.downloadTrace()}>
            Guardar traza
          </button>
        </aside>
      </main>

      <section className={'diagnostics-drawer ' + (diagnosticsOpen ? 'open' : '')} aria-hidden={!diagnosticsOpen}>
        <div className="diagnostics-head">
          <div>
            <p className="eyebrow">REGISTRO TÉCNICO</p>
            <h2>Traza LUPA</h2>
          </div>
          <span>{snapshot.trace.length} eventos locales</span>
        </div>
        <div className="trace-table" role="table" aria-label="Traza de mensajes LUPA">
          {snapshot.trace.slice(-40).reverse().map((entry, index) => (
            <div className="trace-row" role="row" key={entry.at + index}>
              <time>{entry.at.slice(11, 23)}</time>
              <b>{entry.direction}</b>
              <span>{entry.event}</span>
              <code>{entry.detail || '—'}</code>
            </div>
          ))}
        </div>
      </section>

      <div className="sr-only" aria-live="polite">
        Estado del visor: {phaseLabel(snapshot.phase)}.
      </div>
    </div>
  );
}

function Cartridge({
  image,
  index,
  selected,
  onSelect
}: {
  image: CatalogImage;
  index: number;
  selected: boolean;
  onSelect: () => void;
}) {
  const hue = (hashCode(image.imageId) + index * 47) % 360;
  return (
    <button
      className={'cartridge ' + (selected ? 'selected' : '')}
      onClick={onSelect}
      style={{ '--cart-hue': hue } as React.CSSProperties}
      aria-pressed={selected}
    >
      <span className="cart-ridge" aria-hidden="true" />
      <span className="cart-label">
        <span className="cart-emblem" aria-hidden="true"><i/><i/><i/></span>
        <strong>{image.imageId}</strong>
        <small>{image.width} × {image.height} · {image.imageVersion}</small>
      </span>
      <span className="cart-chip">Z{image.maxLevel}</span>
    </button>
  );
}

function StatusLamp({ phase }: { phase: ClientSnapshot['phase'] }) {
  return (
    <div className="status-lamp" data-phase={phase}>
      <span aria-hidden="true" />
      <div>
        <small>CANAL LUPA</small>
        <strong>{phaseLabel(phase)}</strong>
      </div>
    </div>
  );
}

function StatusReadout({ snapshot }: { snapshot: ClientSnapshot }) {
  const manifest = snapshot.manifest;
  return (
    <>
      <div className="readout primary">
        <small>IMAGEN ACTIVA</small>
        <strong>{manifest ? manifest.imageId : 'SIN CARTUCHO'}</strong>
      </div>
      <div className="readout">
        <small>VERSIÓN</small>
        <strong>{manifest?.imageVersion ?? '—'}</strong>
      </div>
      <div className="readout">
        <small>DIMENSIONES</small>
        <strong>{manifest ? manifest.width + '×' + manifest.height : '—'}</strong>
      </div>
      <div className="readout">
        <small>NIVEL</small>
        <strong>{snapshot.plan ? 'z' + snapshot.plan.appliedLevel : '—'}</strong>
      </div>
      <div className="readout">
        <small>PLAN</small>
        <strong>{snapshot.serverDone ? 'ENVIADO' : snapshot.pendingDecodes > 0 ? 'PROCESANDO' : '—'}</strong>
      </div>
    </>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function PrismMark() {
  return (
    <svg className="prism-mark" viewBox="0 0 84 84" aria-hidden="true">
      <defs>
        <linearGradient id="g1" x1="0" x2="1"><stop stopColor="#1d4e9e"/><stop offset="1" stopColor="#3b82d0"/></linearGradient>
        <linearGradient id="g2" x1="0" x2="1"><stop stopColor="#ef755f"/><stop offset="1" stopColor="#c83f4d"/></linearGradient>
      </defs>
      <path d="M12 49 35 12l37 15-20 44z" fill="url(#g1)" />
      <path d="m35 12 17 59 20-44z" fill="#f2c84b" />
      <path d="m12 49 40 22-17-59z" fill="url(#g2)" opacity=".92" />
      <circle cx="43" cy="40" r="12" fill="#e9dfc7" stroke="#252a35" strokeWidth="5" />
      <circle cx="43" cy="40" r="5" fill="#1d4e9e" />
    </svg>
  );
}

function phaseLabel(phase: ClientSnapshot['phase']): string {
  const labels: Record<ClientSnapshot['phase'], string> = {
    disconnected: 'Desconectada',
    connecting: 'Conectando',
    ready: 'Lista',
    opening: 'Abriendo imagen',
    receiving: 'Recibiendo',
    processing: 'Procesando',
    observing: 'Lista para observar',
    error: 'Error'
  };
  return labels[phase];
}

function hashCode(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index++) hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  return hash;
}

function formatBytes(value: number): string {
  if (value < 1024) return value + ' B';
  if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KiB';
  return (value / (1024 * 1024)).toFixed(1) + ' MiB';
}
