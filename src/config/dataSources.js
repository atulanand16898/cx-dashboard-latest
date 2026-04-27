import {
  AutodeskConstructionCloudLogo,
  CxAlloyLogo,
  FacilityGridLogo,
  PrimaveraLogo,
} from '../components/branding/SourceLogos'

export const DATA_SOURCES = [
  {
    key: 'cxalloy',
    label: 'CxAlloy',
    eyebrow: 'Current production source',
    description: 'Use the standard CXA-backed workspace, metrics, and project access rules.',
    usernameHint: 'admin or name@example.com',
    helper: 'Admin: admin / admin123. Assigned users can still sign in with email / same email password.',
    logo: CxAlloyLogo,
    status: 'available',
  },
  {
    key: 'facilitygrid',
    label: 'Facility Grid',
    eyebrow: 'Facility Grid dataset',
    description: 'Open the Facility Grid-backed workspace with its own isolated admin account and data scope.',
    usernameHint: 'fg-admin or name@example.com',
    helper: 'Admin: fg-admin / fgadmin123. Assigned users can still sign in with email / same email password.',
    logo: FacilityGridLogo,
    status: 'available',
  },
  {
    key: 'primavera',
    label: 'Primavera P6',
    eyebrow: 'Oracle scheduling',
    description: 'Create isolated Primavera baseline-report projects, upload XER baselines, and configure progress measurement from processed schedule resources.',
    usernameHint: 'p6-admin',
    helper: 'Admin: p6-admin / p6admin123.',
    logo: PrimaveraLogo,
    status: 'available',
  },
  {
    key: 'autodesk-construction-cloud',
    label: 'Autodesk Construction Cloud',
    eyebrow: 'ACC integration',
    description: 'Autodesk Construction Cloud will be added as another workspace option soon.',
    usernameHint: 'username',
    helper: 'This source is not live yet.',
    logo: AutodeskConstructionCloudLogo,
    status: 'coming-soon',
  },
]

export const getDataSource = (key) =>
  DATA_SOURCES.find((item) => item.key === key) || null
