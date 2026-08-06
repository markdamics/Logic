interface IconProps {
  size?: number;
}

const common = {
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.5,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
};

export function TerminalIcon({ size = 20 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <rect x="3" y="4" width="18" height="16" rx="1" />
      <path d="M7 9l3 3-3 3" />
      <line x1="12" y1="15" x2="16" y2="15" />
    </svg>
  );
}

export function DashboardIcon({ size = 17 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <rect x="3" y="3" width="7" height="9" />
      <rect x="14" y="3" width="7" height="5" />
      <rect x="14" y="12" width="7" height="9" />
      <rect x="3" y="16" width="7" height="5" />
    </svg>
  );
}

export function LogsIcon({ size = 17 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <line x1="4" y1="6" x2="20" y2="6" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="18" x2="14" y2="18" />
    </svg>
  );
}

export function SourcesIcon({ size = 17 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <ellipse cx="12" cy="6" rx="8" ry="3" />
      <path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6" />
      <path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
    </svg>
  );
}

export function AlertsIcon({ size = 17 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <path d="M6 8a6 6 0 1112 0c0 4 1.5 5 1.5 6.5H4.5C4.5 13 6 12 6 8z" />
      <path d="M9.5 17a2.5 2.5 0 005 0" />
    </svg>
  );
}

export function EditIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z" />
    </svg>
  );
}

export function DeleteIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <path d="M4 7h16" />
      <path d="M6 7l1 13a1 1 0 001 1h8a1 1 0 001-1l1-13" />
      <path d="M9 7V4a1 1 0 011-1h4a1 1 0 011 1v3" />
    </svg>
  );
}

export function TestIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <path d="M4 4v6h6" />
      <path d="M20 20v-6h-6" />
      <path d="M5 15a7.5 7.5 0 0012.5 3.5L20 16" />
      <path d="M19 9a7.5 7.5 0 00-12.5-3.5L4 8" />
    </svg>
  );
}

export function CollapseIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} {...common}>
      <rect x="3" y="4" width="18" height="16" />
      <line x1="10" y1="4" x2="10" y2="20" />
    </svg>
  );
}

export function PlusIcon({ size = 22 }: IconProps) {
  return (
    <svg width={size} height={size} {...common} stroke="var(--color-primary)">
      <path d="M12 16V4" />
      <path d="M6 10l6-6 6 6" />
      <path d="M4 20h16" />
    </svg>
  );
}
