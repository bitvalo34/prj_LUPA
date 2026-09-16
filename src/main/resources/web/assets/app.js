(async () => {
  const status = document.querySelector('#catalog-status');
  const output = document.querySelector('#catalog-output');
  try {
    const response = await fetch('/api/catalog', { cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const catalog = await response.json();
    status.textContent = 'Catálogo local disponible (fixture E19 o catálogo publicado configurado).';
    output.textContent = JSON.stringify(catalog, null, 2);
  } catch (error) {
    status.textContent = `Catálogo no disponible: ${error.message}`;
  }
})();
