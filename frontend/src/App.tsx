import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import { ProtectedRoute } from './components/ProtectedRoute'
import { GameRoute } from './components/GameRoute'
import { LoginPage } from './pages/LoginPage'
import { SignupPage } from './pages/SignupPage'
import { DashboardPage } from './pages/DashboardPage'
import { AccountsPage } from './pages/AccountsPage'
import { BankStatementPage } from './pages/BankStatementPage'
import { TransferPage } from './pages/TransferPage'
import { LoansPage } from './pages/LoansPage'
import { FXMarketsPage } from './pages/FXMarketsPage'
import { TradingPage } from './pages/TradingPage'
import { SettingsPage } from './pages/SettingsPage'
import { GameLobbyPage } from './pages/GameLobbyPage'
import { GamePlayPage } from './pages/GamePlayPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 10_000,
    },
  },
})

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Toaster position="top-right" toastOptions={{ duration: 4000 }} />
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route element={<GameRoute />}>
            <Route path="/game" element={<GameLobbyPage />} />
            <Route path="/game/play/:sessionId" element={<GamePlayPage />} />
          </Route>
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/accounts" element={<AccountsPage />} />
            <Route path="/statement" element={<BankStatementPage />} />
            <Route path="/transfer" element={<TransferPage />} />
            <Route path="/loans" element={<LoansPage />} />
            <Route path="/fx" element={<FXMarketsPage />} />
            <Route path="/trading" element={<TradingPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}

export default App
