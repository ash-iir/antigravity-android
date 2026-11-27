(function(){
/**
 * patches/account_switcher/install.js - Persistent Account Vault UI & instant
 * switcher for Settings -> Accounts and Settings -> Account.
 *
 * Uses only the host app's native Settings React primitives (LU, PU, NU, OU, BI,
 * Dgb, Ew, eE) so visual design stays 100% consistent with App Settings.
 */
var global = typeof window !== 'undefined' ? window : this;

if (!global.__agAccountSwitcher) {
  var lastSyncedSignature = '';
  var ACCOUNTS_EVENT = 'ag:accounts-updated';

  function getBridge() {
    return global.Android || global.AndroidBridge || null;
  }

  function getBridgeToken() {
    return String(global.__AG_NATIVE_BRIDGE_TOKEN__ || '');
  }

  function buildProfilePayload(userStatus, authContext) {
    var email = (userStatus && typeof userStatus.email === 'string') ? userStatus.email.trim() : '';
    var name = (userStatus && typeof userStatus.name === 'string') ? userStatus.name.trim() : '';
    var avatarUrl = (userStatus && typeof userStatus.profilePictureUrl === 'string')
      ? userStatus.profilePictureUrl.trim() : '';
    var tier = '';
    if (userStatus && userStatus.userTier) {
      tier = String(userStatus.userTier.description || userStatus.userTier.name || '').trim();
    }
    var projectId = (authContext && typeof authContext.project === 'string') ? authContext.project.trim() : '';
    var location = (authContext && typeof authContext.location === 'string') ? authContext.location.trim() : '';
    var isGcpTos = Boolean(authContext && authContext.isGcpTos);
    return {
      email: email,
      name: name,
      avatarUrl: avatarUrl,
      tier: tier,
      projectId: projectId,
      location: location,
      isGcpTos: isGcpTos
    };
  }

  function readSavedAccounts() {
    try {
      var bridge = getBridge();
      var token = getBridgeToken();
      if (!bridge || !token || typeof bridge.getSavedAccounts !== 'function') {
        return [];
      }
      var raw = bridge.getSavedAccounts(token);
      var parsed = JSON.parse(raw || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      return [];
    }
  }

  function notifyUpdated() {
    try {
      global.dispatchEvent(new CustomEvent(ACCOUNTS_EVENT));
    } catch (ignored) {}
  }

  function syncProfile(userStatus, authContext, force) {
    try {
      var bridge = getBridge();
      var token = getBridgeToken();
      if (!bridge || !token || typeof bridge.syncCurrentAccount !== 'function') {
        return [];
      }
      var payload = buildProfilePayload(userStatus, authContext);
      if (!payload.email && !force) {
        return [];
      }
      var sig = JSON.stringify(payload);
      if (!force && sig === lastSyncedSignature) {
        return [];
      }
      lastSyncedSignature = sig;
      var raw = bridge.syncCurrentAccount(token, sig);
      var parsed = JSON.parse(raw || '[]');
      notifyUpdated();
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      return [];
    }
  }

  async function callCoreAuthLogout(authService) {
    if (authService && authService._lsClient && typeof authService._lsClient.authLogout === 'function') {
      await authService._lsClient.authLogout({});
      return;
    }
    var endpoints = global.__AG_ENDPOINTS__ || {};
    var base = endpoints.core || endpoints.core_http || '';
    var csrf = global.__AG_CSRF__ || '';
    var headers = { 'Content-Type': 'application/json' };
    if (csrf) {
      headers['x-codeium-csrf-token'] = csrf;
    }
    await fetch(base + '/exa.language_server_pb.LanguageServerService/AuthLogout', {
      method: 'POST',
      headers: headers,
      body: '{}'
    });
  }

  function formatSubtitle(acc) {
    var parts = [];
    if (acc.name && acc.email) {
      parts.push(acc.email);
    }
    if (acc.tier) {
      parts.push(acc.tier);
    } else if (acc.projectId) {
      parts.push('Project: ' + acc.projectId);
    } else if (!acc.name && !acc.email) {
      parts.push(acc.authMethod === 'enterprise' ? 'Enterprise Account' : 'Google Account');
    } else if (!acc.name && acc.email) {
      parts.push(acc.authMethod === 'enterprise' ? 'Enterprise' : 'Google Account');
    }
    return parts.join(' \u00b7 ');
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function SavedAccountsComponent(props) {
    var E = props.React;
    var PU = props.PU;
    var NU = props.NU;
    var OU = props.OU;
    var BI = props.BI;
    var Dgb = props.Dgb;
    var Ew = props.Ew;
    var eE = props.eE;
    var userStatus = props.userStatus;
    var authState = props.authState;
    var authService = props.authService;
    var refreshUserStatus = props.refreshUserStatus;
    var getStoreState = props.getStoreState;
    var closeSettings = props.closeSettings;
    var isRemoteControl = Boolean(props.isRemoteControl);

    var _accountsState = E.useState(function () { return readSavedAccounts(); });
    var accounts = _accountsState[0];
    var setAccounts = _accountsState[1];

    var _busyState = E.useState('');
    var busy = _busyState[0];
    var setBusy = _busyState[1];

    var _errState = E.useState('');
    var errorMsg = _errState[0];
    var setErrorMsg = _errState[1];

    var reloadList = E.useCallback(function () {
      setAccounts(readSavedAccounts());
    }, []);

    E.useEffect(function () {
      if (userStatus && userStatus.email) {
        syncProfile(userStatus, authState && authState.context, false);
      }
      reloadList();
    }, [
      userStatus && userStatus.email,
      userStatus && userStatus.name,
      userStatus && userStatus.profilePictureUrl,
      userStatus && userStatus.userTier && userStatus.userTier.description,
      authState && authState.state,
      reloadList
    ]);

    E.useEffect(function () {
      var handler = function () { reloadList(); };
      global.addEventListener(ACCOUNTS_EVENT, handler);
      return function () { global.removeEventListener(ACCOUNTS_EVENT, handler); };
    }, [reloadList]);

    var handleAddAccount = E.useCallback(async function () {
      if (busy || isRemoteControl || !authService) return;
      setErrorMsg('');
      setBusy('__add__');
      try {
        if (authState && authState.state === 'signedIn') {
          syncProfile(userStatus, authState.context, true);
        }
        await authService.logout();
        if (typeof closeSettings === 'function') {
          closeSettings();
        }
        if (typeof authService.showLoginFlow === 'function') {
          authService.showLoginFlow();
        }
      } catch (e) {
        setErrorMsg((e && e.message) ? e.message : 'Unable to start sign-in flow.');
      } finally {
        setBusy('');
      }
    }, [busy, isRemoteControl, authService, authState, userStatus, closeSettings]);

    var handleSignOut = E.useCallback(async function () {
      if (busy || isRemoteControl || !authService) return;
      setErrorMsg('');
      setBusy('__signout__');
      try {
        syncProfile(userStatus, authState && authState.context, true);
        await authService.logout();
        reloadList();
      } catch (e) {
        setErrorMsg((e && e.message) ? e.message : 'Failed to sign out.');
      } finally {
        setBusy('');
      }
    }, [busy, isRemoteControl, authService, authState, userStatus, reloadList]);

    var handleRemove = E.useCallback(function (acc) {
      if (busy || !acc || !acc.id) return;
      setErrorMsg('');
      try {
        var bridge = getBridge();
        var token = getBridgeToken();
        if (bridge && token && typeof bridge.removeSavedAccount === 'function') {
          var raw = bridge.removeSavedAccount(token, acc.id);
          var parsed = JSON.parse(raw || '[]');
          setAccounts(Array.isArray(parsed) ? parsed : readSavedAccounts());
        }
      } catch (e) {
        setErrorMsg('Failed to remove saved account.');
      }
    }, [busy]);

    var handleSwitch = E.useCallback(async function (acc) {
      if (busy || isRemoteControl || !acc || !acc.id) return;
      var bridge = getBridge();
      var token = getBridgeToken();
      if (!bridge || !token || typeof bridge.activateSavedAccount !== 'function') {
        setErrorMsg('Native account switcher bridge is unavailable.');
        return;
      }
      setErrorMsg('');
      setBusy(acc.id);
      try {
        var outgoingJson = (authState && authState.state === 'signedIn')
          ? JSON.stringify(buildProfilePayload(userStatus, authState.context))
          : '{}';

        // 1. Evict core in-memory auth & CodeAssist caches without flashing signedOut UI.
        if (authState && authState.state !== 'signedOut') {
          try {
            await callCoreAuthLogout(authService);
          } catch (ignored) {}
        }

        // 2. Atomically write target account's token payload to jetski-standalone-oauth-token.
        var res = JSON.parse(bridge.activateSavedAccount(token, acc.id, outgoingJson) || '{}');
        if (!res || !res.ok) {
          setErrorMsg((res && res.error) ? res.error : 'Failed to switch account.');
          reloadList();
          return;
        }

        lastSyncedSignature = '';

        // 3. Hydrate and validate the newly activated token in language_server + Redux store.
        if (authService && typeof authService.checkCurrentAuthStatus === 'function') {
          await authService.checkCurrentAuthStatus();
        }
        if (typeof refreshUserStatus === 'function') {
          await refreshUserStatus();
        }

        // 4. Verify target account actually took effect; retry once or cleanly restart core if needed.
        var targetEmail = (acc.email || '').trim().toLowerCase();
        var currentState = typeof getStoreState === 'function' ? getStoreState() : null;
        var activeEmail = (currentState && currentState.userStatus && currentState.userStatus.email)
          ? String(currentState.userStatus.email).trim().toLowerCase()
          : '';

        if (targetEmail && activeEmail !== targetEmail) {
          await sleep(250);
          if (authService && typeof authService.checkCurrentAuthStatus === 'function') {
            await authService.checkCurrentAuthStatus();
          }
          if (typeof refreshUserStatus === 'function') {
            await refreshUserStatus();
          }
          currentState = typeof getStoreState === 'function' ? getStoreState() : null;
          activeEmail = (currentState && currentState.userStatus && currentState.userStatus.email)
            ? String(currentState.userStatus.email).trim().toLowerCase()
            : '';
        }

        var nextAuth = authService && authService._authState;
        if (nextAuth && (nextAuth.state === 'loginError' || nextAuth.state === 'signedOut')) {
          var errText = (nextAuth.context && (nextAuth.context.errorMessage || nextAuth.context.ineligibleMessage)) || '';
          setErrorMsg(errText || ('Saved session for ' + (acc.email || 'this account') + ' expired. Sign in again to refresh it.'));
        } else if (targetEmail && activeEmail && activeEmail !== targetEmail) {
          if (typeof bridge.restartCoreForAccountSwitch === 'function') {
            bridge.restartCoreForAccountSwitch(token);
            return;
          }
        }

        if (currentState && currentState.userStatus && currentState.userStatus.email) {
          syncProfile(currentState.userStatus, nextAuth && nextAuth.context, true);
        }
        reloadList();
      } catch (e) {
        setErrorMsg((e && e.message) ? e.message : 'Unable to switch account.');
      } finally {
        setBusy('');
      }
    }, [busy, isRemoteControl, authService, authState, userStatus, refreshUserStatus, getStoreState, reloadList]);

    var headerRight = E.createElement(BI, {
      variant: 'primary',
      size: 'sm',
      disabled: !!busy || isRemoteControl,
      icon: busy === '__add__'
        ? E.createElement(eE, { size: 12 })
        : E.createElement(Ew, { name: 'add', size: 14 }),
      onClick: handleAddAccount
    }, busy === '__add__' ? 'Preparing...' : 'Add Account');

    var rows = accounts.length === 0
      ? E.createElement(OU, {
          label: 'No saved accounts yet',
          description: 'Accounts you sign into are saved automatically so you can switch back in one tap.'
        })
      : accounts.map(function (acc) {
          var isActive = Boolean(acc.isActive) && !(authState && authState.state === 'signedOut');
          var displayName = acc.name || acc.email || 'Antigravity Account';
          var subtitle = formatSubtitle(acc);
          var switchingThis = busy === acc.id;

          return E.createElement('div', {
            key: acc.id,
            'data-testid': 'settings-saved-account-row',
            className: 'py-2.5 px-3 w-full bg-transparent flex items-center justify-between gap-3'
          },
            E.createElement('div', { className: 'flex items-center gap-2.5 min-w-0 flex-1' },
              E.createElement(Dgb, {
                src: acc.avatarUrl || undefined,
                name: acc.name || undefined,
                email: acc.email || undefined,
                size: 30
              }),
              E.createElement('div', { className: 'flex-1 min-w-0 flex flex-col gap-0.5' },
                E.createElement('div', { className: 'flex items-center gap-1.5 min-w-0' },
                  E.createElement('span', { className: 'font-medium text-foreground truncate' }, displayName),
                  isActive && E.createElement('span', {
                    'data-testid': 'settings-row-badge',
                    className: 'text-[10px] font-semibold leading-none bg-secondary text-secondary-foreground px-1.5 py-0.5 rounded-full select-none border border-border shrink-0'
                  }, 'Active')
                ),
                subtitle && E.createElement('span', { className: 'text-sm text-muted-foreground truncate' }, subtitle)
              )
            ),
            E.createElement('div', { className: 'flex items-center gap-1.5 shrink-0' },
              isActive
                ? E.createElement(BI, {
                    variant: 'secondary',
                    size: 'sm',
                    disabled: !!busy || isRemoteControl,
                    onClick: handleSignOut
                  }, busy === '__signout__' ? 'Signing Out...' : 'Sign Out')
                : E.createElement(E.Fragment, null,
                    E.createElement(BI, {
                      variant: 'secondary',
                      size: 'sm',
                      disabled: !!busy || isRemoteControl,
                      icon: switchingThis ? E.createElement(eE, { size: 12 }) : void 0,
                      onClick: function () { handleSwitch(acc); }
                    }, switchingThis ? 'Switching...' : 'Switch'),
                    E.createElement(BI, {
                      variant: 'ghost',
                      size: 'icon-sm',
                      disabled: !!busy,
                      title: 'Remove saved account',
                      'aria-label': 'Remove saved account',
                      onClick: function () { handleRemove(acc); }
                    }, E.createElement(Ew, { name: 'close', size: 14 }))
                  )
            )
          );
        });

    return E.createElement(PU, {
      title: 'Saved Accounts',
      count: accounts.length,
      headerRight: headerRight
    },
      E.createElement(NU, null, rows),
      errorMsg && E.createElement('div', {
        className: 'text-sm text-red-500 pt-1'
      }, errorMsg)
    );
  }

  function renderSection(ctx) {
    if (!ctx || !ctx.React) return null;
    return ctx.React.createElement(SavedAccountsComponent, ctx);
  }

  function renderScreen(ctx) {
    if (!ctx || !ctx.React || !ctx.LU) return null;
    var E = ctx.React;
    var LU = ctx.LU;
    var PU = ctx.PU;
    var NU = ctx.NU;
    var OU = ctx.OU;
    return E.createElement(LU, {
      title: 'Accounts',
      description: 'View every account you have signed into and switch instantly without re-authenticating.'
    },
      renderSection(ctx),
      E.createElement(PU, { title: 'Session Persistence' },
        E.createElement(NU, null,
          E.createElement(OU, {
            label: 'Automatic Credential Vault',
            description: 'Signed-in account tokens and profile details are stored locally in owner-only app storage for one-tap switching.'
          })
        )
      )
    );
  }

  global.__agAccountSwitcher = {
    syncProfile: syncProfile,
    readSavedAccounts: readSavedAccounts,
    renderSection: renderSection,
    renderScreen: renderScreen
  };
}


})();
