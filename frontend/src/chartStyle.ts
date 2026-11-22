export function readChartStyle() {
  const styles = getComputedStyle(document.documentElement)
  const value = (name: string) => styles.getPropertyValue(name).trim()
  return {
    textStyle: { fontFamily: styles.fontFamily, color: value('--text') },
    muted: value('--text-muted'),
    border: value('--border'),
    tooltip: {
      backgroundColor: value('--panel'),
      borderColor: value('--border'),
      textStyle: { color: value('--text'), fontFamily: styles.fontFamily },
    },
    success: value('--success'),
    danger: value('--danger'),
    warning: value('--warning'),
  }
}
