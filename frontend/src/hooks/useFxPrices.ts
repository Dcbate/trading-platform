import { useLivePrices } from './useLivePrices'

export function useFxPrices() {
  return useLivePrices('/v1/fx/prices', 'fx-prices')
}
