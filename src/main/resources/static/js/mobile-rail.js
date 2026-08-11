        if (!window.__mobileLeftRailControllerBound) {
            window.__mobileLeftRailControllerBound = true;

            const mobilePhoneQuery = window.matchMedia('(max-width: 600px)');

            function isMobilePhoneViewport() {
                return mobilePhoneQuery.matches;
            }

            function isMobileLeftRailOpen() {
                return document.body.classList.contains('mobile-left-rail-open');
            }

            function getAppSwitcherButtons() {
                return document.querySelectorAll('.app-switcher');
            }

            function syncAppSwitcherState() {
                const expanded = isMobileLeftRailOpen() ? 'true' : 'false';
                getAppSwitcherButtons().forEach(button => {
                    button.setAttribute('aria-expanded', expanded);
                });
            }

            function openMobileLeftRail() {
                if (!isMobilePhoneViewport()) return;
                document.body.classList.add('mobile-left-rail-open');
                syncAppSwitcherState();
            }

            function closeMobileLeftRail() {
                if (!isMobileLeftRailOpen()) {
                    syncAppSwitcherState();
                    return;
                }
                document.body.classList.remove('mobile-left-rail-open');
                syncAppSwitcherState();
            }

            function toggleMobileLeftRail() {
                if (!isMobilePhoneViewport()) return;
                if (isMobileLeftRailOpen()) {
                    closeMobileLeftRail();
                } else {
                    openMobileLeftRail();
                }
            }

            function isLeftRailInteractiveTarget(target) {
                return !!(target && target.closest && target.closest('#left-rail .workspace-item, #left-rail .session-item'));
            }

            document.addEventListener('click', event => {
                const switcher = event.target && event.target.closest ? event.target.closest('.app-switcher') : null;
                if (switcher) {
                    if (!isMobilePhoneViewport()) return;
                    event.preventDefault();
                    toggleMobileLeftRail();
                    return;
                }

                if (!isMobilePhoneViewport() || !isMobileLeftRailOpen()) return;
                if (isLeftRailInteractiveTarget(event.target)) {
                    closeMobileLeftRail();
                    return;
                }

                const rail = document.getElementById('left-rail');
                if (rail && rail.contains(event.target)) return;
                closeMobileLeftRail();
            }, true);

            document.addEventListener('keydown', event => {
                if (event.key === 'Escape' && isMobileLeftRailOpen()) {
                    closeMobileLeftRail();
                }
            }, true);

            mobilePhoneQuery.addEventListener('change', () => {
                if (!isMobilePhoneViewport()) {
                    closeMobileLeftRail();
                    return;
                }
                syncAppSwitcherState();
            });

            function getReviewToggleButtons() {
                return document.querySelectorAll('#toggle-review-rail-btn');
            }

            function getReviewPanel() {
                return document.getElementById('review');
            }

            function syncReviewToggleState() {
                const review = getReviewPanel();
                const reviewOpen = !!(review && review.dataset.open === 'true');
                const ariaExpanded = reviewOpen ? 'true' : 'false';
                const label = reviewOpen ? 'Close review panel' : 'Open review panel';
                document.body.classList.toggle('mobile-review-open', isMobilePhoneViewport() && reviewOpen);
                getReviewToggleButtons().forEach(button => {
                    button.setAttribute('aria-expanded', ariaExpanded);
                    button.setAttribute('aria-label', label);
                    button.setAttribute('title', label);
                });
            }

            function isReviewInteractiveTarget(target) {
                return !!(target && target.closest && target.closest('#review, #toggle-review-rail-btn'));
            }

            function triggerReviewToggle() {
                const button = document.getElementById('toggle-review-rail-btn');
                if (button) button.click();
            }

            document.addEventListener('click', event => {
                const reviewToggle = event.target && event.target.closest ? event.target.closest('#toggle-review-rail-btn') : null;
                if (reviewToggle) {
                    return;
                }

                if (!isMobilePhoneViewport() || !document.body.classList.contains('mobile-review-open')) return;
                if (isReviewInteractiveTarget(event.target)) return;
                const reviewPanel = getReviewPanel();
                if (reviewPanel && reviewPanel.contains(event.target)) return;
                triggerReviewToggle();
            }, true);

            document.addEventListener('keydown', event => {
                if (event.key === 'Escape' && document.body.classList.contains('mobile-review-open')) {
                    triggerReviewToggle();
                }
            }, true);

            mobilePhoneQuery.addEventListener('change', () => {
                syncReviewToggleState();
            });

            document.body.addEventListener('htmx:afterSwap', syncReviewToggleState, true);
            syncReviewToggleState();

            document.body.addEventListener('htmx:afterSwap', syncAppSwitcherState, true);
            syncAppSwitcherState();
        }
