import { useLivePrices } from './useLivePrices'

export function useStockPrices() {
  return useLivePrices('/v1/stocks/prices', 'stock-prices')
}
