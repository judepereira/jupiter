(function () {
    let chart;

    function number(value) {
        return value == null ? 'N/A' : new Intl.NumberFormat().format(value);
    }

    function render(container) {
        const dataNode = container.querySelector('[data-usage-data]');
        const canvas = container.querySelector('[data-usage-canvas]');
        if (!dataNode || !canvas || typeof Chart === 'undefined') return;
        const points = JSON.parse(dataNode.dataset.usageData || '[]');
        const models = [...new Map(points.map(point => [point.modelKey, point.modelLabel])).entries()];
        const labels = [...new Set(points.map(point => point.hour))];
        const palette = ['#2563eb', '#d97706', '#059669', '#dc2626', '#7c3aed', '#db2777'];
        const datasets = models.flatMap(([key, label], index) => [
            {label: label + ' reported input tokens', data: labels.map(hour => value(points, hour, key, 'input')), backgroundColor: palette[index % palette.length], stack: 'input'},
            {label: label + ' reported output tokens', data: labels.map(hour => value(points, hour, key, 'output')), backgroundColor: palette[index % palette.length] + '80', stack: 'output'}
        ]);
        if (chart) chart.destroy();
        chart = new Chart(canvas, {type: 'bar', data: {labels, datasets}, options: {
            responsive: true, maintainAspectRatio: false,
            scales: {x: {stacked: true, ticks: {callback: value => labels[value]?.replace('T', ' ').replace(':00:00Z', ' UTC')}}, y: {stacked: true, beginAtZero: true}},
            plugins: {legend: {position: 'bottom'}}
        }});
        const sum = name => points.some(point => point[name] != null)
            ? points.reduce((total, point) => total + (point[name] ?? 0), 0)
            : null;
        setMetric(container, '[data-usage-requests]', sum('requests'), 'Reported requests');
        setMetric(container, '[data-usage-total]', sum('total'), 'Reported total tokens');
        setMetric(container, '[data-usage-input]', sum('input'), 'Reported input tokens');
        setMetric(container, '[data-usage-output]', sum('output'), 'Reported output tokens');
        container.querySelector('[data-usage-empty]').hidden = points.length > 0;
        canvas.closest('.settings-usage-canvas-wrap').hidden = points.length === 0;
    }

    function setMetric(container, selector, value, label) {
        const element = container.querySelector(selector);
        element.textContent = number(value);
        element.setAttribute('aria-label', value == null ? label + ': unavailable' : label + ': ' + number(value));
    }

    function value(points, hour, key, direction) {
        const point = points.find(item => item.hour === hour && item.modelKey === key);
        return point?.[direction] ?? null;
    }

    document.addEventListener('shown.bs.tab', event => {
        if (event.target.matches('[data-settings-usage-tab]')) {
            const container = document.querySelector('[data-settings-usage-container]');
            if (container && !container.dataset.loaded) {
                container.dataset.loaded = 'true';
                htmx.trigger(container, 'settings-usage-load');
            }
        }
    });
    document.addEventListener('htmx:afterSwap', event => {
        const chartContainer = event.target.querySelector?.('[data-settings-usage-chart]');
        if (chartContainer) render(chartContainer);
    });
    document.addEventListener('click', event => {
        const button = event.target.closest?.('[data-usage-range]');
        if (!button) return;
        const container = button.closest('[data-settings-usage-container]');
        container.dataset.loaded = 'true';
        htmx.ajax('GET', '/ui/settings/usage?range=' + button.dataset.usageRange, {target: container, swap: 'innerHTML'});
    });
})();
