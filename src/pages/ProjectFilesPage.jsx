import React, { useEffect, useMemo, useRef, useState } from 'react'
import { FolderPlus, Upload, Download, FolderOpen, FileText, Trash2 } from 'lucide-react'
import toast from 'react-hot-toast'
import { useProject } from '../context/ProjectContext'
import { projectFilesApi } from '../services/api'

const STATUS_OPTIONS = [
  { value: 'approved', label: 'Approved' },
  { value: 'open', label: 'Open' },
  { value: 'inprogress', label: 'In Progress' },
  { value: 'na', label: 'N/A' },
]

function formatBytes(value) {
  if (!value && value !== 0) return '—'
  if (value >= 1e9) return `${(value / 1e9).toFixed(2)} GB`
  if (value >= 1e6) return `${(value / 1e6).toFixed(2)} MB`
  if (value >= 1e3) return `${(value / 1e3).toFixed(1)} KB`
  return `${value} B`
}

function formatDate(value) {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return value
  }
}

function statusBadgeColor(status) {
  switch (status) {
    case 'approved':
      return { color: '#22c55e', background: 'rgba(34,197,94,0.10)', border: 'rgba(34,197,94,0.24)' }
    case 'inprogress':
      return { color: '#f59e0b', background: 'rgba(245,158,11,0.10)', border: 'rgba(245,158,11,0.24)' }
    case 'na':
      return { color: '#94a3b8', background: 'rgba(148,163,184,0.10)', border: 'rgba(148,163,184,0.24)' }
    default:
      return { color: '#38bdf8', background: 'rgba(56,189,248,0.10)', border: 'rgba(56,189,248,0.24)' }
  }
}

export default function ProjectFilesPage() {
  const { activeProject } = useProject()
  const fileInputRef = useRef(null)
  const [data, setData] = useState({ folders: [], files: [], statusOptions: [] })
  const [selectedFolderId, setSelectedFolderId] = useState(null)
  const [folderName, setFolderName] = useState('')
  const [loading, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [deletingFolderId, setDeletingFolderId] = useState(null)
  const [deletingFileId, setDeletingFileId] = useState(null)

  const projectId = activeProject?.externalId

  const load = async () => {
    if (!projectId) return
    setLoading(true)
    try {
      const response = await projectFilesApi.list(projectId)
      const payload = response.data?.data || { folders: [], files: [], statusOptions: [] }
      setData(payload)
      setSelectedFolderId((current) => (
        payload.folders.some((folder) => folder.id === current)
          ? current
          : payload.folders?.[0]?.id || null
      ))
    } catch (error) {
      toast.error(error.response?.data?.message || 'Failed to load project files')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [projectId])

  const selectedFolder = useMemo(
    () => data.folders.find((folder) => folder.id === selectedFolderId) || null,
    [data.folders, selectedFolderId]
  )

  const visibleFiles = useMemo(() => {
    if (!selectedFolderId) return data.files
    return data.files.filter((file) => file.folderId === selectedFolderId)
  }, [data.files, selectedFolderId])

  const handleCreateFolder = async () => {
    if (!projectId || !folderName.trim()) return
    setCreating(true)
    try {
      await projectFilesApi.createFolder(projectId, folderName.trim())
      setFolderName('')
      await load()
      toast.success('Folder created')
    } catch (error) {
      toast.error(error.response?.data?.message || 'Failed to create folder')
    } finally {
      setCreating(false)
    }
  }

  const handleUpload = async (event) => {
    const chosenFiles = Array.from(event.target.files || [])
    if (!projectId || !selectedFolderId || !chosenFiles.length) return
    setUploading(true)
    try {
      await projectFilesApi.upload(projectId, selectedFolderId, chosenFiles)
      await load()
      toast.success(`${chosenFiles.length} file(s) uploaded`)
    } catch (error) {
      toast.error(error.response?.data?.message || 'Upload failed')
    } finally {
      setUploading(false)
      event.target.value = ''
    }
  }

  const handleStatusChange = async (fileId, status) => {
    try {
      await projectFilesApi.updateStatus(projectId, fileId, status)
      setData((current) => ({
        ...current,
        files: current.files.map((file) => file.id === fileId ? { ...file, status, updatedAt: new Date().toISOString() } : file),
      }))
      toast.success('Status updated')
    } catch (error) {
      toast.error(error.response?.data?.message || 'Status update failed')
    }
  }

  const handleDownload = async (fileId, fileName) => {
    try {
      const response = await projectFilesApi.download(projectId, fileId)
      const url = URL.createObjectURL(new Blob([response.data]))
      const link = document.createElement('a')
      link.href = url
      link.download = fileName
      link.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      toast.error(error.response?.data?.message || 'Download failed')
    }
  }

  const handleDeleteFolder = async (folder) => {
    if (!projectId || !folder?.id) return
    if (!window.confirm(`Delete folder "${folder.name}" and all files inside it?`)) return

    setDeletingFolderId(folder.id)
    try {
      await projectFilesApi.deleteFolder(projectId, folder.id)
      await load()
      setSelectedFolderId((current) => (current === folder.id ? null : current))
      toast.success('Folder deleted')
    } catch (error) {
      toast.error(error.response?.data?.message || 'Failed to delete folder')
    } finally {
      setDeletingFolderId(null)
    }
  }

  const handleDeleteFile = async (file) => {
    if (!projectId || !file?.id) return
    if (!window.confirm(`Delete file "${file.name}"?`)) return

    setDeletingFileId(file.id)
    try {
      await projectFilesApi.deleteFile(projectId, file.id)
      await load()
      toast.success('File deleted')
    } catch (error) {
      toast.error(error.response?.data?.message || 'Failed to delete file')
    } finally {
      setDeletingFileId(null)
    }
  }

  if (!activeProject) {
    return (
      <div style={{ padding: 24, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--bg-card)', color: 'var(--text-secondary)' }}>
        Select a project to manage folders and files.
      </div>
    )
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '280px minmax(0, 1fr)', gap: 18 }}>
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
          <FolderOpen size={18} color="#38bdf8" />
          <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)' }}>Folders</div>
        </div>
        <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
          <input
            value={folderName}
            onChange={(e) => setFolderName(e.target.value)}
            placeholder="Create folder"
            style={{ flex: 1, background: 'var(--bg-muted)', border: '1px solid var(--border)', color: 'var(--text-primary)', borderRadius: 10, padding: '10px 12px' }}
          />
          <button onClick={handleCreateFolder} disabled={creating || !folderName.trim()} style={{ borderRadius: 10, border: '1px solid rgba(56,189,248,0.24)', background: 'rgba(56,189,248,0.10)', color: '#38bdf8', padding: '0 12px', cursor: 'pointer' }}>
            <FolderPlus size={16} />
          </button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {data.folders.map((folder) => (
            <div
              key={folder.id}
              style={{
                borderRadius: 12,
                border: `1px solid ${selectedFolderId === folder.id ? 'rgba(14,165,233,0.28)' : 'var(--border)'}`,
                background: selectedFolderId === folder.id ? 'rgba(14,165,233,0.10)' : 'transparent',
                padding: '12px 14px',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
                <button
                  onClick={() => setSelectedFolderId(folder.id)}
                  style={{
                    textAlign: 'left',
                    border: 'none',
                    background: 'transparent',
                    padding: 0,
                    cursor: 'pointer',
                    minWidth: 0,
                    flex: 1,
                    fontFamily: 'inherit',
                  }}
                >
                  <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>{folder.name}</div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>{folder.fileCount} files</div>
                </button>
                <button
                  onClick={() => handleDeleteFolder(folder)}
                  disabled={deletingFolderId === folder.id}
                  title="Delete folder"
                  style={{
                    borderRadius: 10,
                    border: '1px solid rgba(239,68,68,0.24)',
                    background: 'rgba(239,68,68,0.10)',
                    color: '#f87171',
                    padding: 8,
                    cursor: deletingFolderId === folder.id ? 'not-allowed' : 'pointer',
                    opacity: deletingFolderId === folder.id ? 0.6 : 1,
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Trash2 size={15} />
                </button>
              </div>
            </div>
          ))}
          {!data.folders.length && !loading ? (
            <div style={{ fontSize: 13, color: '#64748b', paddingTop: 8 }}>Create your first folder to start uploading.</div>
          ) : null}
        </div>
      </div>

      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 16 }}>
          <div>
            <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)' }}>
              {selectedFolder ? `${selectedFolder.name} Files` : 'Project Files'}
            </div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
              Upload files into a folder and track their status as approved, open, inprogress, or n/a.
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <input ref={fileInputRef} type="file" multiple hidden onChange={handleUpload} />
            <button
              onClick={() => fileInputRef.current?.click()}
              disabled={!selectedFolderId || uploading}
              style={{ borderRadius: 10, border: '1px solid rgba(34,197,94,0.24)', background: 'rgba(34,197,94,0.10)', color: '#22c55e', padding: '10px 14px', cursor: 'pointer', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}
            >
              <Upload size={16} />
              {uploading ? 'Uploading...' : 'Upload Files'}
            </button>
          </div>
        </div>

        <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 12 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: 'rgba(148,163,184,0.06)' }}>
                <th style={thStyle}>File</th>
                <th style={thStyle}>Folder</th>
                <th style={thStyle}>Size</th>
                <th style={thStyle}>Uploaded</th>
                <th style={thStyle}>Status</th>
                <th style={thStyle}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleFiles.map((file) => {
                const badge = statusBadgeColor(file.status)
                return (
                  <tr key={file.id} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={tdStyle}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <FileText size={16} color="#94a3b8" />
                        <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>{file.name}</span>
                      </div>
                    </td>
                    <td style={tdStyle}>{file.folderName}</td>
                    <td style={tdStyle}>{formatBytes(file.sizeBytes)}</td>
                    <td style={tdStyle}>{formatDate(file.uploadedAt)}</td>
                    <td style={tdStyle}>
                      <select
                        value={file.status}
                        onChange={(e) => handleStatusChange(file.id, e.target.value)}
                        style={{
                          borderRadius: 999,
                          border: `1px solid ${badge.border}`,
                          background: badge.background,
                          color: badge.color,
                          padding: '7px 10px',
                          fontWeight: 700,
                          textTransform: 'capitalize',
                        }}
                      >
                        {(data.statusOptions?.length ? data.statusOptions : STATUS_OPTIONS.map((item) => item.value)).map((value) => (
                          <option key={value} value={value}>{value}</option>
                        ))}
                      </select>
                    </td>
                    <td style={tdStyle}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
                        <button onClick={() => handleDownload(file.id, file.name)} style={{ border: 'none', background: 'transparent', color: '#38bdf8', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6, fontWeight: 600 }}>
                          <Download size={15} />
                          Download
                        </button>
                        <button
                          onClick={() => handleDeleteFile(file)}
                          disabled={deletingFileId === file.id}
                          style={{
                            border: 'none',
                            background: 'transparent',
                            color: '#f87171',
                            cursor: deletingFileId === file.id ? 'not-allowed' : 'pointer',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 6,
                            fontWeight: 600,
                            opacity: deletingFileId === file.id ? 0.6 : 1,
                          }}
                        >
                          <Trash2 size={15} />
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
              {!visibleFiles.length ? (
                <tr>
                  <td colSpan={6} style={{ padding: 24, textAlign: 'center', color: '#64748b' }}>
                    {selectedFolderId ? 'No files uploaded in this folder yet.' : 'Select a folder to view uploaded files.'}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

const thStyle = {
  textAlign: 'left',
  padding: '12px 14px',
  fontSize: 12,
  color: '#94a3b8',
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
}

const tdStyle = {
  padding: '14px',
  fontSize: 14,
  color: '#cbd5e1',
}
