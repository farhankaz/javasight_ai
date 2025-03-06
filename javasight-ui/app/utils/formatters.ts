export const formatNumber = (value: number | undefined | null): string => {
  if (typeof value !== 'number') return '0'
  
  try {
    return value.toLocaleString()
  } catch {
    return value.toString()
  }
} 