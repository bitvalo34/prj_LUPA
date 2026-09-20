import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type WheelEvent
} from 'react';
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
  pendingPresentations: 0,
  serverDone: false,
  doneSentTiles: null,
  managedBitmapBytes: 0,
  activeViewRect: null,
  viewMode: 'uniform',
  detailOffset: 0,
  focus: null,
  zoom: 1,
  error: null,
  trace: []
};

export function App() {
  const sampleMode = useMemo(() => new URLSearchParams(window.location.search).get('sample') === '1', []);
  const supportError = useMemo(() => {
    if (typeof Worker === 'undefined') return 'Este navegador no ofrece Web Workers.';
    if (typeof createImageBitmap !== 'function') return 'Este navegador no ofrece createImageBitmap.';
    if (typeof ResizeObserver === 'undefined') return 'Este navegador no ofrece ResizeObserver.';
    return null;
  }, []);
  const client = useMemo(
    () => supportError ? null : new LupaClient(sampleMode ? () => new SampleTransport() : undefined),
    [sampleMode, supportError]
  );
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const catalogRequestRef = useRef(0);
  const catalogAbortRef = useRef<AbortController | null>(null);
  const dragRef = useRef<{
    pointerId: number;
    lastX: number;
    lastY: number;
    startX: number;
    startY: number;
    moved: boolean;
  } | null>(null);
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
    const requestId = ++catalogRequestRef.current;
    catalogAbortRef.current?.abort();
    const controller = new AbortController();
    catalogAbortRef.current = controller;
    setCatalogBusy(true);
    setCatalogError(null);
    try {
      const next = await loadCatalog(controller.signal);
      if (requestId === catalogRequestRef.current) setCatalog(next);
    } catch (error) {
      if (controller.signal.aborted || requestId !== catalogRequestRef.current) return;
      setCatalog(null);
      setCatalogError(error instanceof Error ? error.message : 'No se pudo cargar el catálogo');
    } finally {
      if (requestId === catalogRequestRef.current) setCatalogBusy(false);
    }
  }

  useEffect(() => {
    if (!client) return;
    const unsubscribe = client.subscribe(setSnapshot);
    if (canvasRef.current) client.attachCanvas(canvasRef.current);
    client.connect();
    if (!sampleMode) void refreshCatalog();
    return () => {
      catalogAbortRef.current?.abort();
      unsubscribe();
      client.destroy();
    };
  }, [client, sampleMode]);

  useEffect(() => {
    if (!client) return;
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

  useEffect(() => {
    const updateVisibility = () => {
      document.documentElement.classList.toggle('lupa-hidden', document.hidden);
    };
    updateVisibility();
    document.addEventListener('visibilitychange', updateVisibility);
    return () => {
      document.removeEventListener('visibilitychange', updateVisibility);
      document.documentElement.classList.remove('lupa-hidden');
    };
  }, []);

  function selectImage(image: CatalogImage) {
    client?.selectImage(image);
  }

  function stagePoint(event: { clientX: number; clientY: number }): { x: number; y: number } | null {
    const stage = stageRef.current;
    if (!stage) return null;
    const bounds = stage.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
  }

  function onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (!client || !snapshot.manifest || event.button !== 0) return;
    const point = stagePoint(event);
    if (!point) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = {
      pointerId: event.pointerId,
      lastX: point.x,
      lastY: point.y,
      startX: point.x,
      startY: point.y,
      moved: false
    };
    event.currentTarget.classList.add('dragging');
  }

  function onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!client || !drag || drag.pointerId !== event.pointerId) return;
    const point = stagePoint(event);
    if (!point) return;
    const dx = point.x - drag.lastX;
    const dy = point.y - drag.lastY;
    if (Math.hypot(point.x - drag.startX, point.y - drag.startY) > 4) drag.moved = true;
    drag.lastX = point.x;
    drag.lastY = point.y;
    if (drag.moved) client.panByCss(dx, dy);
  }

  function finishPointer(event: ReactPointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    if (!client || !drag || drag.pointerId !== event.pointerId) return;
    const point = stagePoint(event);
    if (!drag.moved && point && snapshot.viewMode === 'focus') client.focusAtCss(point.x, point.y);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    event.currentTarget.classList.remove('dragging');
    dragRef.current = null;
  }

  function onWheel(event: WheelEvent<HTMLDivElement>) {
    if (!client || !snapshot.manifest) return;
    event.preventDefault();
    const point = stagePoint(event);
    if (!point) return;
    client.zoomAtCss(point.x, point.y, event.deltaY < 0 ? 0.8 : 1.25);
  }

  function onStageKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!client || !snapshot.manifest) return;
    const actions: Record<string, () => void> = {
      ArrowLeft: () => client.panByFraction(-0.1, 0),
      ArrowRight: () => client.panByFraction(0.1, 0),
      ArrowUp: () => client.panByFraction(0, -0.1),
      ArrowDown: () => client.panByFraction(0, 0.1),
      '+': () => client.zoomBy(0.8),
      '=': () => client.zoomBy(0.8),
      '-': () => client.zoomBy(1.25),
      '0': () => client.resetView()
    };
    const action = actions[event.key];
    if (!action) return;
    event.preventDefault();
    action();
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
          <button className="console-button small" onClick={() => client?.reconnect()} disabled={!client}>
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
              <div
                className={'screen-stage ' + (snapshot.manifest ? 'interactive' : '')}
                ref={stageRef}
                tabIndex={snapshot.manifest ? 0 : -1}
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={finishPointer}
                onPointerCancel={finishPointer}
                onWheel={onWheel}
                onKeyDown={onStageKeyDown}
                aria-label="Visor navegable. Arrastra para desplazar, usa la rueda para acercar o alejar y las flechas para mover la vista."
              >
                <canvas ref={canvasRef} aria-label="Imagen científica compuesta progresivamente" />
                {!snapshot.manifest && (
                  <div className="screen-idle">
                    <div className="idle-prism" aria-hidden="true" />
                    <strong>Selecciona un cartucho</strong>
                    <span>La miniatura real llegará como TILE z=0.</span>
                  </div>
                )}
                {(snapshot.phase === 'error' || supportError) && (
                  <div className="screen-error" role="alert">
                    <strong>{supportError ? 'Navegador no compatible' : 'La señal se interrumpió'}</strong>
                    <span>{supportError ?? snapshot.error}</span>
                  </div>
                )}
              </div>
            </div>
            <div className="viewer-controls">
              <StatusReadout snapshot={snapshot} />
              <NavigationControls client={client} snapshot={snapshot} />
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
          <Metric label="Modo" value={snapshot.viewMode === 'focus' ? 'Lente' : 'Uniforme'} />
          <Metric label="Detalle" value={String(snapshot.detailOffset)} />
          <Metric label="Zoom" value={snapshot.zoom.toFixed(2) + '×'} />
          <Metric label="TILE recibidos" value={String(snapshot.receivedTiles)} />
          <Metric label="Dibujados" value={String(snapshot.drawnTiles)} />
          <Metric label="Descartados" value={String(snapshot.discardedTiles)} />
          <Metric label="RELEASE" value={String(snapshot.releases)} />
          <Metric label="Decodificando" value={String(snapshot.pendingDecodes)} />
          <Metric label="Por pintar" value={String(snapshot.pendingPresentations)} />
          <Metric label="Bitmaps LUPA" value={formatBytes(snapshot.managedBitmapBytes)} />
          <Metric label="Región VIEW" value={formatRect(snapshot.activeViewRect)} />
          <p className="memory-note">Memoria administrada por LUPA; no representa toda la RAM/GPU del navegador.</p>
          {snapshot.error && snapshot.phase !== 'error' && <p className="recoverable-error" role="status">{snapshot.error}</p>}
          <button
            className="console-button secondary"
            onClick={() => setDiagnosticsOpen((value) => !value)}
            aria-expanded={diagnosticsOpen}
          >
            {diagnosticsOpen ? 'Ocultar diagnóstico' : 'Abrir diagnóstico'}
          </button>
          <button className="console-button secondary" onClick={() => client?.downloadTrace()} disabled={!client}>
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
      style={{ '--cart-hue': hue } as CSSProperties}
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

function NavigationControls({
  client,
  snapshot
}: {
  client: LupaClient | null;
  snapshot: ClientSnapshot;
}) {
  const disabled = !client || !snapshot.manifest;
  return (
    <div className="navigation-console" aria-label="Controles de navegación y resolución">
      <div className="navigation-group">
        <span>Vista</span>
        <button
          className={snapshot.viewMode === 'uniform' ? 'active' : ''}
          onClick={() => client?.setViewMode('uniform')}
          aria-pressed={snapshot.viewMode === 'uniform'}
          disabled={disabled}
        >Uniforme</button>
        <button
          className={snapshot.viewMode === 'focus' ? 'active' : ''}
          onClick={() => client?.setViewMode('focus')}
          aria-pressed={snapshot.viewMode === 'focus'}
          disabled={disabled}
        >Lente</button>
      </div>

      <div className="navigation-group detail-selector">
        <span>Detalle</span>
        {([-2, -1, 0] as const).map((offset) => (
          <button
            key={offset}
            className={snapshot.detailOffset === offset ? 'active' : ''}
            onClick={() => client?.setDetailOffset(offset)}
            aria-pressed={snapshot.detailOffset === offset}
            disabled={disabled}
            title={offset === 0 ? 'Detalle máximo solicitado' : 'Reduce el nivel solicitado'}
          >{offset}</button>
        ))}
      </div>

      <div className="navigation-group zoom-controls">
        <span>Zoom {snapshot.zoom.toFixed(2)}×</span>
        <button onClick={() => client?.zoomBy(1.25)} disabled={disabled} aria-label="Alejar">−</button>
        <button onClick={() => client?.zoomBy(0.8)} disabled={disabled} aria-label="Acercar">+</button>
        <button onClick={() => client?.resetView()} disabled={disabled}>Restablecer</button>
      </div>

      {snapshot.viewMode === 'focus' && (
        <label className="focus-radius">
          <span>Radio {snapshot.focus?.radiusPx ?? 144}px</span>
          <input
            type="range"
            min="32"
            max="512"
            step="16"
            value={snapshot.focus?.radiusPx ?? 144}
            onChange={(event) => client?.setFocusRadius(Number(event.currentTarget.value))}
            disabled={disabled}
          />
        </label>
      )}

      <p className="navigation-hint">
        Arrastra para desplazar y usa la rueda para cambiar escala.
        {snapshot.viewMode === 'focus' ? ' Haz clic para fijar el foco de la lente.' : ''}
      </p>
    </div>
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


function formatRect(rect: ClientSnapshot['activeViewRect']): string {
  if (!rect) return '—';
  const concise = (value: number) => Number(value.toFixed(1)).toString();
  return concise(rect.x) + ',' + concise(rect.y) + ' ' + concise(rect.width) + '×' + concise(rect.height);
}
