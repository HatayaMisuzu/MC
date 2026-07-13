import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { InstanceTable } from './InstanceTable'
import type { Instance } from '../types'

const instance: Instance = {
  id: 'fabric-one', launcherId: 'pcl2-one', name: 'Fabric 1.21.1', minecraftVersion: '1.21.1',
  loader: 'FABRIC', loaderVersion: '0.16.14', gameDir: 'C:\\Fixture\\Fabric', javaRequired: 21,
  javaConfigured: '', confidence: 'HIGH', isolation: 'VERSION_DIRECTORY', compatible: true,
  installed: false, mode: 'FULL',
}

describe('InstanceTable', () => {
  it('renders real compatibility fields and selects a row', async () => {
    const select = vi.fn()
    render(<InstanceTable instances={[instance]} selectedId="" onSelect={select} />)
    expect(screen.getByText('Fabric 1.21.1')).toBeInTheDocument()
    expect(screen.getByText('FABRIC 0.16.14')).toBeInTheDocument()
    expect(screen.getByText('需要 Java 21')).toBeInTheDocument()
    await userEvent.click(screen.getByText('Fabric 1.21.1'))
    expect(select).toHaveBeenCalledWith('fabric-one')
  })
})
