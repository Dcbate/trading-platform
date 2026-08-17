import { useLivePrices } from './useLivePrices'

export function useCryptoPrices() {
  return useLivePrices('/v1/crypto/prices', 'crypto-prices')
}
